package com.example.translationapplication

import android.content.Context
import android.graphics.*
import android.view.View

data class TranslationBlock(
    val box: List<List<Float>>,
    val translated: String
)

class OverlayView(context: Context) : View(context) {

    private var blocks: List<TranslationBlock> = emptyList()
    private var sourceW = 0
    private var sourceH = 0

    private val overlayScaleX: Float
        get() = if (sourceW > 0 && width > 0) width.toFloat() / sourceW else 1f
    private val overlayScaleY: Float
        get() = if (sourceH > 0 && height > 0) height.toFloat() / sourceH else 1f

    private val boxPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
        color = Color.argb(255, 20, 20, 20)
    }

    private val borderPaint = Paint().apply {
        color = Color.argb(255, 60, 140, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }

    fun setSourceSize(sw: Int, sh: Int) {
        sourceW = sw
        sourceH = sh
        println("📐 setSourceSize: source=${sw}x${sh} → view=${width}x${height} → scale=${overlayScaleX}x${overlayScaleY}")
        invalidate()
    }

    fun updateBlocks(newBlocks: List<TranslationBlock>) {
        blocks = newBlocks
        invalidate()
    }

    fun clear() {
        blocks = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (block in blocks) {
            drawBlock(canvas, block)
        }
    }

    private fun drawBlock(canvas: Canvas, block: TranslationBlock) {
        if (block.box.isEmpty()) return

        val pts = block.box.map {
            PointF(it[0] * overlayScaleX, it[1] * overlayScaleY)
        }

        val left   = pts.minOf { it.x }
        val top    = pts.minOf { it.y }
        val right  = pts.maxOf { it.x }
        val bottom = pts.maxOf { it.y }

        if (right - left < 5f || bottom - top < 5f) return

        val padX = 8f
        val padY = 6f
        val rect = RectF(left - padX, top - padY, right + padX, bottom + padY)

        val boxHeight = rect.height()
        val boxWidth  = rect.width()

        canvas.drawRoundRect(rect, 8f, 8f, boxPaint)
        canvas.drawRoundRect(rect, 8f, 8f, borderPaint)

        val maxTextSize = (boxHeight * 0.7f).coerceAtMost(36f).coerceAtLeast(10f)
        textPaint.textSize = maxTextSize
        textPaint.alpha = 255

        while (textPaint.measureText(block.translated) > boxWidth - 16f && textPaint.textSize > 10f) {
            textPaint.textSize -= 1f
        }

        val textHeight = textPaint.descent() - textPaint.ascent()
        val textY = top - padY + (boxHeight - textHeight) / 2f - textPaint.ascent()

        canvas.drawText(block.translated, left - padX + 8f, textY, textPaint)
    }
}