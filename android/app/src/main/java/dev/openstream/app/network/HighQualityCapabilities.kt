package dev.openstream.app.network

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Surface

object HighQualityCapabilities {
    data class Support(val available: Boolean, val reason: String)

    /** Capture and compression support are separate from a verified USB route and receiver support. */
    fun camera4kSdr(context: Context, cameraId: String, fps: Int, bitrate: Int): Support = try {
        require(fps > 0 && bitrate > 0)
        val characteristics = context.getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val size = map?.getOutputSizes(Surface::class.java)?.firstOrNull { it.width == 3840 && it.height == 2160 }
        if (size == null) Support(false, "Selected camera does not expose 3840 × 2160 capture")
        else if (map.getOutputMinFrameDuration(Surface::class.java, size) > 1_000_000_000L / fps)
            Support(false, "Selected camera cannot capture 4K at $fps fps")
        else {
            val hardware = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
                codec.isEncoder && codec.isHardwareAccelerated &&
                    codec.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } &&
                    runCatching {
                        val caps = codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) &&
                            caps.videoCapabilities.areSizeAndRateSupported(3840, 2160, fps.toDouble()) &&
                            caps.videoCapabilities.bitrateRange.contains(bitrate)
                    }.getOrDefault(false)
            }
            if (hardware) Support(true, "4K SDR camera and hardware encoder available")
            else Support(false, "No hardware AVC encoder supports 4K at $fps fps and this bitrate")
        }
    } catch (error: Exception) {
        Support(false, "Cannot verify 4K support: ${error.message}")
    }

    // shortcut: the current AVC/OBS pipeline is SDR, enable HDR only after end-to-end Main10 validation.
    fun hdr(): Support = Support(false,
        "HDR unavailable: this release uses 8-bit AVC; 10-bit camera, HEVC Main10, mux color metadata and OBS HDR rendering are required")
}
