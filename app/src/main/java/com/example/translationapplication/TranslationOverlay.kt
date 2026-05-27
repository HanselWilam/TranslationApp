package com.example.translationapplication

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.view.View
import org.json.JSONArray

data class TextBox(val left: Float, val top: Float, val right: Float, val bottom: Float, val text: String)

class TranslationOverlay(context: Context) : View(context) {
    private var textBoxes = listOf<TextBox>()

    private val backgroundStyle = Paint().apply {
        color = Color.parseColor("#B31E1E1E")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textStyle = Paint().apply {
        color = Color.WHITE
        textSize = 45f
        isAntiAlias = true
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

        val cornerRadius = 16f

        for (box in textBoxes) {
            canvas.drawRoundRect(box.left, box.top, box.right, box.bottom, cornerRadius, cornerRadius, backgroundStyle)
            canvas.drawText(box.text, box.left + 8f, box.bottom - 12f, textStyle)
        }
    }
}