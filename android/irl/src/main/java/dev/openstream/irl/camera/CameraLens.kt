package dev.openstream.irl.camera

import android.hardware.camera2.CameraCharacteristics
import kotlin.math.roundToInt

/** A selectable camera target. Back lenses may be zoom presets on one logical camera. */
data class CameraLens(
    val displayName: String,
    val shortLabel: String,
    val facing: Int,
    /** Camera device to open; null is used only by compatibility defaults. */
    val cameraId: String? = null,
    /** CONTROL_ZOOM_RATIO value for this lens, or null for the default view. */
    val zoomRatio: Float? = null,
) {
    val isBackFacing: Boolean get() = facing == CameraCharacteristics.LENS_FACING_BACK
    val isFrontFacing: Boolean get() = facing == CameraCharacteristics.LENS_FACING_FRONT

    companion object {
        // Fallbacks used before camera characteristics have been enumerated.
        val Back = CameraLens("Back camera", "1×", CameraCharacteristics.LENS_FACING_BACK)
        val Front = CameraLens("Front camera", "Front", CameraCharacteristics.LENS_FACING_FRONT)
    }
}

internal data class PhysicalLensInfo(
    val id: String,
    val focalLengthMm: Float,
    val sensorWidthMm: Float,
)

/** Maps physical focal lengths to seamless logical-camera zoom presets. */
internal fun buildOpticalLensPresets(
    logicalCameraId: String,
    defaultFocalLengthMm: Float,
    defaultSensorWidthMm: Float,
    minZoomRatio: Float,
    maxZoomRatio: Float,
    physicalLenses: List<PhysicalLensInfo>,
): List<CameraLens> {
    if (defaultFocalLengthMm <= 0f || defaultSensorWidthMm <= 0f) return emptyList()
    val defaultFovScale = defaultFocalLengthMm / defaultSensorWidthMm
    val candidates = physicalLenses.mapNotNull { physical ->
        if (physical.focalLengthMm <= 0f || physical.sensorWidthMm <= 0f) return@mapNotNull null
        val ratio = (physical.focalLengthMm / physical.sensorWidthMm) / defaultFovScale
        if (!ratio.isFinite() || ratio < minZoomRatio || ratio > maxZoomRatio) return@mapNotNull null
        CameraLens(
            displayName = "${formatZoomLabel(ratio)} camera",
            shortLabel = formatZoomLabel(ratio),
            facing = CameraCharacteristics.LENS_FACING_BACK,
            cameraId = logicalCameraId,
            zoomRatio = ratio,
        )
    }.sortedBy { it.zoomRatio }

    val unique = mutableListOf<CameraLens>()
    candidates.forEach { lens ->
        val previousIndex = unique.indexOfFirst { existing ->
            kotlin.math.abs((existing.zoomRatio ?: 1f) - (lens.zoomRatio ?: 1f)) < 0.1f
        }
        if (previousIndex < 0) {
            unique += lens
        } else if (lens.shortLabel == "1×") {
            unique[previousIndex] = lens
        }
    }
    return unique
}

internal fun formatZoomLabel(ratio: Float): String {
    val nearestInteger = ratio.roundToInt().toFloat()
    val rounded = if (nearestInteger >= 2f && kotlin.math.abs(ratio - nearestInteger) < 0.35f) {
        nearestInteger
    } else {
        (ratio * 10f).roundToInt() / 10f
    }
    val display = if (kotlin.math.abs(rounded - rounded.toInt()) < 0.001f) {
        rounded.toInt().toString()
    } else {
        String.format(java.util.Locale.ROOT, "%.1f", rounded)
    }
    return "${display}×"
}
