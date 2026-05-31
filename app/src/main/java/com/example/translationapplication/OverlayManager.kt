package com.example.translationapplication

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewTreeObserver
import android.view.WindowManager

class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: OverlayView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun show(sentW: Int, sentH: Int) {
        mainHandler.post {
            if (overlayView != null) return@post

            // 🔧 FIXED: Added FLAG_LAYOUT_NO_LIMITS so the overlay expands completely over
            // the status and nav bars, ensuring 1:1 coordinate alignment with screenshots.
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )

            // 🔧 FIXED: Allows drawing inside the notch area to match full-screen captures
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            val view = OverlayView(context)
            overlayView = view
            windowManager.addView(view, params)

            view.viewTreeObserver.addOnGlobalLayoutListener(
                object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        view.setSourceSize(sentW, sentH)
                        println("✅ Overlay ready: view=${view.width}x${view.height}, sent=${sentW}x${sentH}")
                    }
                }
            )
        }
    }

    fun updateBlocks(blocks: List<TranslationBlock>) {
        mainHandler.post {
            overlayView?.updateBlocks(blocks)
        }
    }

    fun clear() {
        mainHandler.post {
            overlayView?.clear()
        }
    }

    fun dismiss() {
        mainHandler.post {
            overlayView?.let {
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {
                    println("Overlay already removed: ${e.message}")
                }
                overlayView = null
            }
        }
    }
}