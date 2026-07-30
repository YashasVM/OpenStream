package dev.openstream.app.camera

data class SensorRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

object FocusCoordinateMapper {
    fun mapToMeteringRegion(
        normalizedX: Float,
        normalizedY: Float,
        activeArray: SensorRect,
        cropRegion: SensorRect = activeArray,
        rotationDegrees: Int = 0,
        mirrored: Boolean = false,
        regionFraction: Float = 0.08f,
    ): SensorRect {
        require(normalizedX.isFinite() && normalizedY.isFinite())
        require(normalizedX in 0f..1f && normalizedY in 0f..1f)
        require(rotationDegrees.mod(90) == 0)
        require(regionFraction > 0f && regionFraction <= 1f)

        val displayX = if (mirrored) 1f - normalizedX else normalizedX
        val displayY = normalizedY
        val rotation = rotationDegrees.mod(360)
        val (sensorX, sensorY) = when (rotation) {
            0 -> displayX to displayY
            90 -> displayY to (1f - displayX)
            180 -> (1f - displayX) to (1f - displayY)
            270 -> (1f - displayY) to displayX
            else -> error("Rotation must be a multiple of 90")
        }

        val boundedCrop = SensorRect(
            left = cropRegion.left.coerceIn(activeArray.left, activeArray.right - 1),
            top = cropRegion.top.coerceIn(activeArray.top, activeArray.bottom - 1),
            right = cropRegion.right.coerceIn(activeArray.left + 1, activeArray.right),
            bottom = cropRegion.bottom.coerceIn(activeArray.top + 1, activeArray.bottom),
        )
        val centerX = boundedCrop.left + (boundedCrop.width * sensorX).toInt()
        val centerY = boundedCrop.top + (boundedCrop.height * sensorY).toInt()
        val halfWidth = (boundedCrop.width * regionFraction / 2f).toInt().coerceAtLeast(1)
        val halfHeight = (boundedCrop.height * regionFraction / 2f).toInt().coerceAtLeast(1)
        val left = (centerX - halfWidth).coerceIn(boundedCrop.left, boundedCrop.right - 2)
        val top = (centerY - halfHeight).coerceIn(boundedCrop.top, boundedCrop.bottom - 2)
        val right = (centerX + halfWidth).coerceIn(left + 1, boundedCrop.right)
        val bottom = (centerY + halfHeight).coerceIn(top + 1, boundedCrop.bottom)
        return SensorRect(left, top, right, bottom)
    }
}
