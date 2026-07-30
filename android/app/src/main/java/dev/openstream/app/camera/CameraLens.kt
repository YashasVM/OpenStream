package dev.openstream.app.camera

import android.hardware.camera2.CameraCharacteristics
import kotlin.math.abs
import kotlin.math.round

/** A lens option discovered from the cameras exposed by the phone. */
data class CameraLens(
    /** The logical or standalone Camera2 ID that owns this option. */
    val cameraId: String?,
    val displayName: String,
    val shortLabel: String,
    val facing: Int,
    /** Zoom ratio to apply after this camera is selected. */
    val targetZoom: Float = 1f,
) {
    val isBackFacing: Boolean get() = facing == CameraCharacteristics.LENS_FACING_BACK
    val isFrontFacing: Boolean get() = facing == CameraCharacteristics.LENS_FACING_FRONT

    companion object {
        fun defaultBack() = CameraLens(
            cameraId = null,
            displayName = "1×",
            shortLabel = "1×",
            facing = CameraCharacteristics.LENS_FACING_BACK,
        )

        fun selfie(cameraId: String) = CameraLens(
            cameraId = cameraId,
            displayName = "Selfie",
            // Keep the established control-protocol value for paired OBS clients.
            shortLabel = "Front",
            facing = CameraCharacteristics.LENS_FACING_FRONT,
        )
    }
}

/** A physical rear-camera measurement normalized to the 35 mm-equivalent field of view. */
data class CameraLensCandidate(
    val cameraId: String,
    val equivalentFocalLength: Float,
)

/**
 * Converts Camera2 focal-length data into the lens shortcuts shown to the operator.
 *
 * Android does not publish OEM marketing labels (for example, "3x"), so the labels
 * are calculated relative to the candidate closest to the standard 24 mm-equivalent
 * phone main camera. Logical multi-cameras use one logical ID and request the derived
 * zoom ratio, allowing the device HAL to choose the appropriate physical camera.
 */
object CameraLensDiscovery {
    fun rearLenses(
        logicalCameraId: String?,
        candidates: List<CameraLensCandidate>,
        supportsLogicalZoomRatio: Boolean,
    ): List<CameraLens> {
        val uniqueCandidates = candidates
            .filter { it.equivalentFocalLength > 0f }
            .distinctBy { it.cameraId }

        val selectableCandidates = when {
            uniqueCandidates.isEmpty() -> listOf(CameraLensCandidate(logicalCameraId.orEmpty(), 24f))
            logicalCameraId != null && !supportsLogicalZoomRatio -> listOf(uniqueCandidates.first())
            else -> uniqueCandidates
        }
        val main = selectableCandidates.minByOrNull { abs(it.equivalentFocalLength - MAIN_CAMERA_EQUIVALENT_MM) }
            ?: selectableCandidates.first()

        val options = selectableCandidates.map { candidate ->
            val multiplier = candidate.equivalentFocalLength / main.equivalentFocalLength
            val roundedMultiplier = round(multiplier * 10f) / 10f
            CameraLens(
                cameraId = logicalCameraId ?: candidate.cameraId,
                displayName = formatMultiplier(roundedMultiplier),
                shortLabel = formatMultiplier(roundedMultiplier),
                facing = CameraCharacteristics.LENS_FACING_BACK,
                targetZoom = if (logicalCameraId != null) multiplier else 1f,
            )
        }.distinctBy { it.shortLabel }
            .sortedBy { it.targetZoom.takeIf { logicalCameraId != null } ?: labelValue(it.shortLabel) }

        // A phone with one rear camera still gets a useful wide/digital pair.
        return if (options.size == 1) {
            val mainOption = options.single().copy(
                displayName = "1×",
                shortLabel = "1×",
                targetZoom = 1f,
            )
            listOf(mainOption, mainOption.copy(displayName = "5×", shortLabel = "5×", targetZoom = 5f))
        } else {
            options
        }
    }

    private fun formatMultiplier(value: Float): String = if (abs(value - round(value)) < 0.05f) {
        "${round(value).toInt()}×"
    } else {
        "${"%.1f".format(java.util.Locale.US, value)}×"
    }

    private fun labelValue(label: String): Float = label.removeSuffix("×").toFloatOrNull() ?: 1f

    private const val MAIN_CAMERA_EQUIVALENT_MM = 24f
}
