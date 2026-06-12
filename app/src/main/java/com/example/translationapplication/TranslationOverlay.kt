package com.example.translationapplication

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import androidx.core.graphics.withTranslation
import org.json.JSONArray

data class TextBox(val left: Float, val top: Float, val right: Float, val bottom: Float, val text: String)

class TranslationOverlay(context: Context) : View(context) {
    private var textBoxes = listOf<TextBox>()
    private var backgroundColor = Color.parseColor("#E61E1E1E")
    private var textColor = Color.parseColor("#F8F9FA")
    val boxPadding = 16f
    val cornerRadius = 16f

    private val backgroundPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = TextPaint().apply {
        textSize = 42f
        isAntiAlias = true
        isFakeBoldText = true
    }

    fun clearBoxes() {
        textBoxes = emptyList()
        postInvalidate()
    }

    fun updateTranslation(jsonString: String) {
        try {
            val jsonArray = JSONArray(jsonString)
            val newBoxes = mutableListOf<TextBox>()

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val box = item.getJSONArray("box")

                val topLeft = box.getJSONArray(0)
                val bottomRight = box.getJSONArray(2)

                newBoxes.add(
                    TextBox(
                        left = topLeft.getDouble(0).toFloat(),
                        top = topLeft.getDouble(1).toFloat(),
                        right = bottomRight.getDouble(0).toFloat(),
                        bottom = bottomRight.getDouble(1).toFloat(),
                        text = item.getString("translated")
                    )
                )
            }
            textBoxes = newBoxes
            postInvalidate()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        backgroundPaint.color = backgroundColor
        textPaint.color = textColor

        for (box in textBoxes) {
            val baseBoxWidth = (box.right - box.left)
            val finalMaxWidth = maxOf(50, baseBoxWidth.toInt() + (boxPadding.toInt() * 2))

            val staticLayout = StaticLayout.Builder.obtain(
                box.text, 0, box.text.length, textPaint, finalMaxWidth
            )
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .build()

            var actualTextWidth = 0f
            for (i in 0 until staticLayout.lineCount) {
                actualTextWidth = maxOf(actualTextWidth, staticLayout.getLineWidth(i))
            }

            val backgroundWidth = staticLayout.width + (boxPadding * 2)
            val backgroundHeight = staticLayout.height.toFloat() + (boxPadding * 2)

            canvas.drawRoundRect(
                box.left,
                box.top,
                box.left + backgroundWidth,
                box.top + backgroundHeight,
                cornerRadius,
                cornerRadius,
                backgroundPaint
            )

            canvas.withTranslation (box.left + boxPadding, box.top + boxPadding) {
                staticLayout.draw(this)
            }
        }
    }
}