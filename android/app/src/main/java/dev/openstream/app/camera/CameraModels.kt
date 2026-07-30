package dev.openstream.app.camera

enum class AuthorityMode(val wireValue: String) {
    Collaborative("collaborative"),
    ObsLock("obs_lock"),
    ;

    companion object {
        fun fromWire(value: String): AuthorityMode? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class CameraActor(val wireValue: String) {
    Camera("camera"),
    Obs("obs"),
    System("system"),
}

enum class ExposureMode(val wireValue: String) {
    Auto("auto"),
    Manual("manual"),
    ;

    companion object {
        fun fromWire(value: String): ExposureMode? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class FocusMode(val wireValue: String) {
    Continuous("continuous"),
    Single("single"),
    Manual("manual"),
    ;

    companion object {
        fun fromWire(value: String): FocusMode? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class FocusActionMode(val wireValue: String) {
    Auto("auto"),
    Lock("lock"),
    ;

    companion object {
        fun fromWire(value: String): FocusActionMode? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class WhiteBalanceMode(val wireValue: String) {
    Auto("auto"),
    Daylight("daylight"),
    Cloudy("cloudy"),
    Incandescent("incandescent"),
    Fluorescent("fluorescent"),
    Manual("manual"),
    ;

    companion object {
        fun fromWire(value: String): WhiteBalanceMode? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class StabilizationMode(val wireValue: String) {
    Off("off"),
    Optical("optical"),
    Video("video"),
    ;

    companion object {
        fun fromWire(value: String): StabilizationMode? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class FocusStatus(val wireValue: String) {
    Inactive("inactive"),
    Scanning("scanning"),
    Focused("focused"),
    NotFocused("not_focused"),
    Passive("passive"),
}

data class IntValueRange(val min: Int, val max: Int) {
    init { require(min <= max) }
    fun contains(value: Int) = value in min..max
    fun clamp(value: Int) = value.coerceIn(min, max)
}

data class LongValueRange(val min: Long, val max: Long) {
    init { require(min <= max) }
    fun contains(value: Long) = value in min..max
    fun clamp(value: Long) = value.coerceIn(min, max)
}

data class FloatValueRange(val min: Float, val max: Float) {
    init { require(min <= max) }
    fun contains(value: Float) = value in min..max
    fun clamp(value: Float) = value.coerceIn(min, max)
}

data class CameraCapabilities(
    val cameraId: String,
    val displayName: String,
    val lensFacing: String,
    val logicalMultiCamera: Boolean,
    val physicalCameraIds: List<String>,
    val manualSensor: Boolean,
    val manualWhiteBalance: Boolean,
    val supportsAwbLock: Boolean,
    val supportsTapFocus: Boolean,
    val supportsAeRegions: Boolean,
    val supportsTorch: Boolean,
    val supportsZoomRatio: Boolean,
    val isoRange: IntValueRange?,
    val shutterRangeNs: LongValueRange?,
    val exposureCompensationRange: IntValueRange?,
    val focusDistanceRange: FloatValueRange?,
    val zoomRange: FloatValueRange,
    val fpsRanges: List<IntValueRange>,
    val focusModes: Set<FocusMode>,
    val whiteBalanceModes: Set<WhiteBalanceMode>,
    val stabilizationModes: Set<StabilizationMode>,
)

data class CameraSettings(
    val exposureMode: ExposureMode = ExposureMode.Auto,
    val iso: Int? = null,
    val shutterNs: Long? = null,
    val exposureCompensation: Int = 0,
    val whiteBalanceMode: WhiteBalanceMode = WhiteBalanceMode.Auto,
    val whiteBalanceKelvin: Int? = null,
    val whiteBalanceTint: Int = 0,
    val whiteBalanceLock: Boolean = false,
    val focusMode: FocusMode = FocusMode.Continuous,
    val focusDistanceDiopters: Float? = null,
    val zoomRatio: Float = 1f,
    val torch: Boolean = false,
    val stabilizationMode: StabilizationMode = StabilizationMode.Off,
    val fps: Int? = null,
)

data class CameraSettingsPatch(
    val exposureMode: ExposureMode? = null,
    val iso: Int? = null,
    val shutterNs: Long? = null,
    val exposureCompensation: Int? = null,
    val whiteBalanceMode: WhiteBalanceMode? = null,
    val whiteBalanceKelvin: Int? = null,
    val whiteBalanceTint: Int? = null,
    val whiteBalanceLock: Boolean? = null,
    val focusMode: FocusMode? = null,
    val focusDistanceDiopters: Float? = null,
    val zoomRatio: Float? = null,
    val torch: Boolean? = null,
    val stabilizationMode: StabilizationMode? = null,
    val fps: Int? = null,
)

data class CameraTelemetry(
    val actualIso: Int? = null,
    val actualShutterNs: Long? = null,
    val actualFocusDistanceDiopters: Float? = null,
    val actualZoomRatio: Float = 1f,
    val actualWhiteBalanceKelvin: Int? = null,
    val focusStatus: FocusStatus = FocusStatus.Inactive,
    val aeState: String = "inactive",
    val awbState: String = "inactive",
    val frameNumber: Long = 0,
    val timestampNs: Long = 0,
)

data class TallyState(
    val program: Boolean = false,
    val preview: Boolean = false,
)

data class CameraState(
    val revision: Long = 0,
    val lastActor: CameraActor = CameraActor.System,
    val authority: AuthorityMode = AuthorityMode.Collaborative,
    val tally: TallyState = TallyState(),
    val settings: CameraSettings = CameraSettings(),
    val telemetry: CameraTelemetry = CameraTelemetry(),
)

sealed interface CameraControlResult {
    data class Applied(val state: CameraState) : CameraControlResult
    data class Conflict(val state: CameraState) : CameraControlResult
    data class Unsupported(val field: String, val reason: String, val state: CameraState) : CameraControlResult
    data class Invalid(val field: String, val reason: String, val state: CameraState) : CameraControlResult
    data class Locked(val state: CameraState) : CameraControlResult
}
