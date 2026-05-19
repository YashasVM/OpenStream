package dev.openstream.app.camera

import android.hardware.camera2.CameraCharacteristics

/**
 * Represents available physical camera lenses.
 *
 * The actual availability of each lens varies by device. Camera2Controller
 * queries the device's camera list and exposes only the lenses that actually
 * exist on the hardware.
 */
enum class CameraLens(
    val displayName: String,
    val shortLabel: String,
    val facing: Int,
    /** Approximate focal-length hint used to disambiguate multiple back cameras. */
    val focalHint: FocalHint = FocalHint.Normal,
) {
    Back("Back camera", "1×", CameraCharacteristics.LENS_FACING_BACK, FocalHint.Normal),
    BackUltrawide("Ultrawide", "0.5×", CameraCharacteristics.LENS_FACING_BACK, FocalHint.Ultrawide),
    BackTelephoto("Telephoto", "2×", CameraCharacteristics.LENS_FACING_BACK, FocalHint.Telephoto),
    Front("Front camera", "Front", CameraCharacteristics.LENS_FACING_FRONT, FocalHint.Normal),
    ;

    val isBackFacing: Boolean get() = facing == CameraCharacteristics.LENS_FACING_BACK
    val isFrontFacing: Boolean get() = facing == CameraCharacteristics.LENS_FACING_FRONT

    enum class FocalHint { Ultrawide, Normal, Telephoto }
}
