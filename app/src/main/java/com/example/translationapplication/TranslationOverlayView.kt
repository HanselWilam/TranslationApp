package com.example.translationapplication

import android.content.Context
import android.graphics.*
import android.view.View

class TranslationOverlayView(context: Context) : View(context) {

    private var items: List<TranslationItem> = emptyList()

    private val bgPaint = Paint().apply {
        color = Color.argb(200, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }

    fun updateItems(newItems: List<TranslationItem>) {
        items = newItems
        invalidate() // redraw
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (item in items) {
            canvas.drawRect(item.x1, item.y1, item.x2, item.y2, bgPaint)
            val textY = item.y1 + (item.y2 - item.y1) / 2 + textPaint.textSize / 3
            canvas.drawText(item.translated, item.x1 + 4, textY, textPaint)
        }
    }
}