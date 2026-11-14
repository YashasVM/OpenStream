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
                codec.isEncoder && codec.isHardwareAccelerated && !codec.isSoftwareOnly &&
                    codec.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } &&
                    runCatching {
                        val caps = codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) &&
                            caps.encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) &&
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

    fun hdr(context: Context, cameraId: String, width: Int = 1920, height: Int = 1080,
            fps: Int = 30, bitrate: Int = 20_000_000): Support {
        if (android.os.Build.VERSION.SDK_INT < 33)
            return Support(false, "HDR requires Android 13 and a ten-bit HLG camera")
        return try {
            val chars = context.getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)
            val tenBit = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT) == true
            val hlg = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                ?.supportedProfiles?.contains(android.hardware.camera2.params.DynamicRangeProfiles.HLG10) == true
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val size = map?.getOutputSizes(Surface::class.java)?.firstOrNull { it.width == width && it.height == height }
            if (!tenBit || !hlg) Support(false, "Selected camera does not support ten-bit HLG capture")
            else if (size == null || map.getOutputMinFrameDuration(Surface::class.java, size) > 1_000_000_000L / fps)
                Support(false, "Selected camera cannot capture this HDR size at $fps fps")
            else {
                val hardware = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
                    codec.isEncoder && codec.isHardwareAccelerated && !codec.isSoftwareOnly &&
                        codec.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) } &&
                        runCatching {
                            val caps = codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                            dev.openstream.app.encoder.supportsMain10(caps.profileLevels.map { it.profile }) &&
                                caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) &&
                                caps.encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) &&
                                caps.videoCapabilities.areSizeAndRateSupported(width, height, fps.toDouble()) &&
                                caps.videoCapabilities.bitrateRange.contains(bitrate)
                        }.getOrDefault(false)
                }
                if (hardware) Support(true, "Ten-bit HLG camera and HEVC Main10 hardware encoder available")
                else Support(false, "No hardware HEVC Main10 encoder supports this HDR profile")
            }
        } catch (error: Exception) {
            Support(false, "Cannot verify HDR support: ${error.message}")
        }
    }
}
