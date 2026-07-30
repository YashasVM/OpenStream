package dev.openstream.app.monitoring

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import dev.openstream.app.audio.AudioLevel
import kotlin.math.max
import kotlin.math.roundToInt

/** Compact microphone meter that shows the actual PCM level before AAC encoding. */
class AudioLevelMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * density
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val tickPaint = Paint(labelPaint).apply {
        color = Color.argb(170, 255, 255, 255)
        textSize = 9f * density
    }
    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(36, 43, 52) }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(210, 4, 7, 11) }
    private var displayedDbfs = AudioLevel.MIN_DBFS
    private var displayedPeakDbfs = AudioLevel.MIN_DBFS
    private var active = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        minimumHeight = (56f * density).roundToInt()
    }

    fun setAudioActive(value: Boolean) {
        active = value
        if (!value) {
            displayedDbfs = AudioLevel.MIN_DBFS
            displayedPeakDbfs = AudioLevel.MIN_DBFS
        }
        invalidate()
    }

    fun setLevel(level: AudioLevel) {
        active = true
        displayedDbfs = smooth(displayedDbfs, level.rmsDbfs, attack = 0.72f, release = 0.18f)
        displayedPeakDbfs = max(level.peakDbfs, displayedPeakDbfs - 1.25f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(bounds, 8f * density, 8f * density, backgroundPaint)

        val labelWidth = 36f * density
        val meterLeft = labelWidth
        val meterRight = width - 10f * density
        val meterTop = 10f * density
        val meterBottom = height - 20f * density
        val segmentGap = 2f * density
        val segmentCount = 30
        val segmentWidth = (meterRight - meterLeft - segmentGap * (segmentCount - 1)) / segmentCount
        val litSegments = if (active) levelToSegment(displayedDbfs, segmentCount) else 0

        labelPaint.color = if (active) Color.WHITE else Color.rgb(150, 158, 168)
        canvas.drawText("MIC", 9f * density, meterTop + 15f * density, labelPaint)
        tickPaint.color = if (active) Color.argb(190, 255, 255, 255) else Color.rgb(130, 138, 148)
        canvas.drawText(if (active) "${displayedDbfs.roundToInt()} dB" else "OFF", 9f * density, meterBottom, tickPaint)

        repeat(segmentCount) { index ->
            val left = meterLeft + index * (segmentWidth + segmentGap)
            val segment = RectF(left, meterTop, left + segmentWidth, meterBottom)
            val paint = if (index < litSegments) colorForSegment(index, segmentCount) else inactivePaint
            canvas.drawRoundRect(segment, density, density, paint)
        }

        if (active) {
            val peakX = meterLeft + levelToRatio(displayedPeakDbfs) * (meterRight - meterLeft)
            canvas.drawRect(peakX - density, meterTop - density, peakX + density, meterBottom + density, peakPaint)
        }
        drawTicks(canvas, meterLeft, meterRight)
    }

    private fun drawTicks(canvas: Canvas, left: Float, right: Float) {
        listOf(-45, -30, -20, -10, -6, -3, 0).forEach { tick ->
            val x = left + levelToRatio(tick.toFloat()) * (right - left)
            val label = tick.toString()
            canvas.drawText(label, x - tickPaint.measureText(label) / 2f, height - 5f * density, tickPaint)
        }
    }

    private fun colorForSegment(index: Int, count: Int): Paint = when {
        index >= count * 28 / 30 -> RED_PAINT
        index >= count * 24 / 30 -> YELLOW_PAINT
        else -> GREEN_PAINT
    }

    private fun levelToSegment(dbfs: Float, count: Int): Int =
        (levelToRatio(dbfs) * count).roundToInt().coerceIn(0, count)

    private fun levelToRatio(dbfs: Float): Float =
        ((dbfs - AudioLevel.MIN_DBFS) / -AudioLevel.MIN_DBFS).coerceIn(0f, 1f)

    private fun smooth(current: Float, target: Float, attack: Float, release: Float): Float {
        val factor = if (target > current) attack else release
        return current + (target - current) * factor
    }

    companion object {
        private val GREEN_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(54, 203, 103) }
        private val YELLOW_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 204, 0) }
        private val RED_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(225, 61, 71) }
    }
}
