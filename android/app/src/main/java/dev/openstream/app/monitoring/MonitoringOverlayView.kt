package dev.openstream.app.monitoring

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.ceil

enum class FrameGuideMode {
    Off,
    Thirds,
    SafeArea,
}

class MonitoringOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val guideShadowPaint = Paint(guidePaint).apply {
        color = Color.argb(140, 0, 0, 0)
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private val zebraPaint = Paint().apply {
        color = Color.argb(150, 255, 204, 0)
        style = Paint.Style.FILL
    }

    var frameGuideMode: FrameGuideMode = FrameGuideMode.Thirds
        set(value) {
            field = value
            invalidate()
        }

    var zebraEnabled: Boolean = false
        set(value) {
            field = value
            if (!value) zebraMask = null
            invalidate()
        }

    private var zebraMask: BooleanArray? = null
    private var maskWidth = 0
    private var maskHeight = 0

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    fun setZebraMask(width: Int, height: Int, mask: BooleanArray) {
        if (width <= 0 || height <= 0 || mask.size != width * height) return
        maskWidth = width
        maskHeight = height
        zebraMask = mask.copyOf()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (zebraEnabled) drawZebras(canvas)
        drawFrameGuides(canvas)
    }

    private fun drawFrameGuides(canvas: Canvas) {
        val lines = when (frameGuideMode) {
            FrameGuideMode.Off -> return
            FrameGuideMode.Thirds -> listOf(
                floatArrayOf(width / 3f, 0f, width / 3f, height.toFloat()),
                floatArrayOf(width * 2f / 3f, 0f, width * 2f / 3f, height.toFloat()),
                floatArrayOf(0f, height / 3f, width.toFloat(), height / 3f),
                floatArrayOf(0f, height * 2f / 3f, width.toFloat(), height * 2f / 3f),
            )
            FrameGuideMode.SafeArea -> emptyList()
        }
        if (frameGuideMode == FrameGuideMode.SafeArea) {
            val actionSafe = RectF(width * 0.05f, height * 0.05f, width * 0.95f, height * 0.95f)
            val titleSafe = RectF(width * 0.10f, height * 0.10f, width * 0.90f, height * 0.90f)
            listOf(actionSafe, titleSafe).forEach { rect ->
                canvas.drawRect(rect, guideShadowPaint)
                canvas.drawRect(rect, guidePaint)
            }
            return
        }
        lines.forEach { line ->
            canvas.drawLine(line[0], line[1], line[2], line[3], guideShadowPaint)
            canvas.drawLine(line[0], line[1], line[2], line[3], guidePaint)
        }
    }

    private fun drawZebras(canvas: Canvas) {
        val mask = zebraMask ?: return
        if (maskWidth == 0 || maskHeight == 0) return
        val cellWidth = width.toFloat() / maskWidth
        val cellHeight = height.toFloat() / maskHeight
        val stripePeriod = 12f * resources.displayMetrics.density
        for (y in 0 until maskHeight) {
            for (x in 0 until maskWidth) {
                if (!mask[y * maskWidth + x]) continue
                val left = x * cellWidth
                val top = y * cellHeight
                val right = ceil((x + 1) * cellWidth.toDouble()).toFloat()
                val bottom = ceil((y + 1) * cellHeight.toDouble()).toFloat()
                if (((left + top) % stripePeriod) < stripePeriod / 2f) {
                    canvas.drawRect(left, top, right, bottom, zebraPaint)
                }
            }
        }
    }
}
