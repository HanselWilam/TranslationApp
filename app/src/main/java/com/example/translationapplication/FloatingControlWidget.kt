package com.example.translationapplication

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.ui.graphics.toArgb
import com.example.translationapplication.ui.theme.PrimaryBlue

class FloatingControlWidget(
    private val context: Context,
    private val onToggle: (Boolean) -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val frameLayout = FrameLayout(context)
    private val iconView = ImageView(context)

    private var isExpanded = true
    private var isPlaying = true
    private val handler = Handler(Looper.getMainLooper())
    private val collapseRunnable = Runnable { collapseWidget() }

    private lateinit var layoutParams: WindowManager.LayoutParams
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    init {
        setupView()
        addToWindow()
        scheduleCollapse()
    }

    private fun setupView() {
        val size = 160
        val padding = 35

        val background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(PrimaryBlue.toArgb())
        }

        frameLayout.background = background
        frameLayout.elevation = 8f

        // Icon
        iconView.setImageResource(android.R.drawable.ic_media_pause)
        iconView.setColorFilter(Color.WHITE)
        iconView.setPadding(padding, padding, padding, padding)

        frameLayout.addView(iconView, FrameLayout.LayoutParams(size, size))

        frameLayout.setOnClickListener {
            if (!isExpanded) {
                expandWidget()
            } else {
                isPlaying = !isPlaying
                val iconRes = if (isPlaying) {
                    android.R.drawable.ic_media_pause
                } else {
                    android.R.drawable.ic_media_play
                }
                iconView.setImageResource(iconRes)
                iconView.setColorFilter(Color.WHITE)
                onToggle(isPlaying)
                scheduleCollapse()
            }
        }
    }

    private fun expandWidget() {
        isExpanded = true
        frameLayout.animate().scaleX(1.0f).scaleY(1.0f).alpha(1.0f).setDuration(200).start()
        iconView.visibility = View.VISIBLE
        scheduleCollapse()
    }

    private fun collapseWidget() {
        isExpanded = false
        frameLayout.animate().scaleX(0.7f).scaleY(0.7f).alpha(0.5f).setDuration(200).start()
        iconView.visibility = View.INVISIBLE
    }

    private fun scheduleCollapse() {
        handler.removeCallbacks(collapseRunnable)
        handler.postDelayed(collapseRunnable, 3000)
    }

    private fun addToWindow() {
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 800
            y = 800
        }

        frameLayout.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) {
                        isDragging = true
                    }

                    layoutParams.x = initialX + dx
                    layoutParams.y = initialY + dy
                    windowManager.updateViewLayout(frameLayout, layoutParams)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        snapToEdge()
                    } else {
                        v.performClick()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(frameLayout, layoutParams)
    }

    private fun snapToEdge() {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val widgetWidth = frameLayout.width.takeIf { it > 0 } ?: 140

        layoutParams.x = if (layoutParams.x < screenWidth / 2) {
            0
        } else {
            screenWidth - widgetWidth
        }

        windowManager.updateViewLayout(frameLayout, layoutParams)
    }
}