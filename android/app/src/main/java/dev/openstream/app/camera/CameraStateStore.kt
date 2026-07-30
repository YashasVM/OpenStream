package dev.openstream.app.camera

import java.util.concurrent.CopyOnWriteArrayList

class CameraStateStore(
    initialCapabilities: CameraCapabilities? = null,
    initialState: CameraState = CameraState(),
) {
    private val listeners = CopyOnWriteArrayList<(CameraState) -> Unit>()
    private var capabilities: CameraCapabilities? = initialCapabilities
    private var state: CameraState = initialState

    @Synchronized
    fun capabilities(): CameraCapabilities? = capabilities

    @Synchronized
    fun snapshot(): CameraState = state

    fun addListener(listener: (CameraState) -> Unit): AutoCloseable {
        listeners += listener
        listener(snapshot())
        return AutoCloseable { listeners -= listener }
    }

    fun setCapabilities(value: CameraCapabilities) {
        val updated = synchronized(this) {
            capabilities = value
            val defaults = defaultsFor(value, state.settings)
            state = state.copy(
                revision = state.revision + 1,
                lastActor = CameraActor.System,
                settings = defaults,
            )
            state
        }
        notifyListeners(updated)
    }

    fun applySettings(
        expectedRevision: Long?,
        actor: CameraActor,
        patch: CameraSettingsPatch,
    ): CameraControlResult {
        val result = synchronized(this) {
            gate(expectedRevision, actor)?.let { return@synchronized it }
            val caps = capabilities
                ?: return@synchronized CameraControlResult.Unsupported("camera", "Camera is not ready", state)
            validatePatch(caps, patch)?.let { return@synchronized it }

            val current = state.settings
            val updatedSettings = current.copy(
                exposureMode = patch.exposureMode ?: current.exposureMode,
                iso = patch.iso ?: current.iso,
                shutterNs = patch.shutterNs ?: current.shutterNs,
                exposureCompensation = patch.exposureCompensation ?: current.exposureCompensation,
                whiteBalanceMode = patch.whiteBalanceMode ?: current.whiteBalanceMode,
                whiteBalanceKelvin = patch.whiteBalanceKelvin ?: current.whiteBalanceKelvin,
                whiteBalanceTint = patch.whiteBalanceTint ?: current.whiteBalanceTint,
                whiteBalanceLock = patch.whiteBalanceLock ?: current.whiteBalanceLock,
                focusMode = patch.focusMode ?: current.focusMode,
                focusDistanceDiopters = patch.focusDistanceDiopters ?: current.focusDistanceDiopters,
                zoomRatio = patch.zoomRatio ?: current.zoomRatio,
                torch = patch.torch ?: current.torch,
                stabilizationMode = patch.stabilizationMode ?: current.stabilizationMode,
                fps = patch.fps ?: current.fps,
            )
            state = state.copy(
                revision = state.revision + 1,
                lastActor = actor,
                settings = updatedSettings,
            )
            CameraControlResult.Applied(state)
        }
        if (result is CameraControlResult.Applied) notifyListeners(result.state)
        return result
    }

    fun applyFocus(
        expectedRevision: Long?,
        actor: CameraActor,
        x: Float,
        y: Float,
    ): CameraControlResult {
        val result = synchronized(this) {
            gate(expectedRevision, actor)?.let { return@synchronized it }
            val caps = capabilities
                ?: return@synchronized CameraControlResult.Unsupported("camera", "Camera is not ready", state)
            if (!caps.supportsTapFocus) {
                return@synchronized CameraControlResult.Unsupported("focus", "Tap focus is unavailable on this lens", state)
            }
            if (!x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f) {
                return@synchronized CameraControlResult.Invalid("focus", "Coordinates must be normalized between 0 and 1", state)
            }
            state = state.copy(revision = state.revision + 1, lastActor = actor)
            CameraControlResult.Applied(state)
        }
        if (result is CameraControlResult.Applied) notifyListeners(result.state)
        return result
    }

    fun setAuthority(
        expectedRevision: Long?,
        actor: CameraActor,
        mode: AuthorityMode,
    ): CameraControlResult {
        val result = synchronized(this) {
            if (expectedRevision != null && expectedRevision != state.revision) {
                return@synchronized CameraControlResult.Conflict(state)
            }
            if (actor == CameraActor.Camera && state.authority == AuthorityMode.ObsLock) {
                return@synchronized CameraControlResult.Locked(state)
            }
            state = state.copy(
                revision = state.revision + 1,
                lastActor = actor,
                authority = mode,
            )
            CameraControlResult.Applied(state)
        }
        if (result is CameraControlResult.Applied) notifyListeners(result.state)
        return result
    }

    fun setTally(program: Boolean, preview: Boolean, actor: CameraActor = CameraActor.Obs): CameraState {
        val updated = synchronized(this) {
            val next = TallyState(program = program, preview = preview && !program)
            if (next == state.tally) return@synchronized state
            state = state.copy(
                revision = state.revision + 1,
                lastActor = actor,
                tally = next,
            )
            state
        }
        notifyListeners(updated)
        return updated
    }

    fun updateTelemetry(telemetry: CameraTelemetry) {
        val updated = synchronized(this) {
            state = state.copy(telemetry = telemetry)
            state
        }
        notifyListeners(updated)
    }

    private fun gate(expectedRevision: Long?, actor: CameraActor): CameraControlResult? {
        if (expectedRevision != null && expectedRevision != state.revision) {
            return CameraControlResult.Conflict(state)
        }
        if (actor == CameraActor.Camera && state.authority == AuthorityMode.ObsLock) {
            return CameraControlResult.Locked(state)
        }
        return null
    }

    private fun validatePatch(
        caps: CameraCapabilities,
        patch: CameraSettingsPatch,
    ): CameraControlResult? {
        if (patch.exposureMode == ExposureMode.Manual && !caps.manualSensor) {
            return CameraControlResult.Unsupported("exposureMode", "Manual sensor control is unavailable", state)
        }
        patch.iso?.let {
            val range = caps.isoRange
                ?: return CameraControlResult.Unsupported("iso", "ISO control is unavailable", state)
            if (!range.contains(it)) return CameraControlResult.Invalid("iso", "ISO must be ${range.min}..${range.max}", state)
        }
        patch.shutterNs?.let {
            val range = caps.shutterRangeNs
                ?: return CameraControlResult.Unsupported("shutterNs", "Shutter control is unavailable", state)
            if (!range.contains(it)) {
                return CameraControlResult.Invalid("shutterNs", "Shutter must be ${range.min}..${range.max} ns", state)
            }
        }
        patch.exposureCompensation?.let {
            val range = caps.exposureCompensationRange
                ?: return CameraControlResult.Unsupported("exposureCompensation", "Exposure compensation is unavailable", state)
            if (!range.contains(it)) {
                return CameraControlResult.Invalid("exposureCompensation", "Compensation must be ${range.min}..${range.max}", state)
            }
        }
        patch.whiteBalanceMode?.let {
            if (it !in caps.whiteBalanceModes) {
                return CameraControlResult.Unsupported("whiteBalanceMode", "White balance mode is unavailable", state)
            }
        }
        patch.whiteBalanceKelvin?.let {
            if (!caps.manualWhiteBalance) {
                return CameraControlResult.Unsupported("whiteBalanceKelvin", "Manual white balance is unavailable", state)
            }
            if (it !in 2_000..12_000) {
                return CameraControlResult.Invalid("whiteBalanceKelvin", "Kelvin must be 2000..12000", state)
            }
        }
        patch.whiteBalanceTint?.let {
            if (!caps.manualWhiteBalance) {
                return CameraControlResult.Unsupported("whiteBalanceTint", "Manual white balance is unavailable", state)
            }
            if (it !in -100..100) {
                return CameraControlResult.Invalid("whiteBalanceTint", "Tint must be -100..100", state)
            }
        }
        if (patch.whiteBalanceLock == true && !caps.supportsAwbLock) {
            return CameraControlResult.Unsupported("whiteBalanceLock", "White balance lock is unavailable", state)
        }
        patch.focusMode?.let {
            if (it !in caps.focusModes) return CameraControlResult.Unsupported("focusMode", "Focus mode is unavailable", state)
        }
        patch.focusDistanceDiopters?.let {
            val range = caps.focusDistanceRange
                ?: return CameraControlResult.Unsupported("focusDistanceDiopters", "Manual focus is unavailable", state)
            if (!range.contains(it)) {
                return CameraControlResult.Invalid("focusDistanceDiopters", "Focus distance must be ${range.min}..${range.max}", state)
            }
        }
        patch.zoomRatio?.let {
            if (!caps.zoomRange.contains(it)) {
                return CameraControlResult.Invalid("zoomRatio", "Zoom must be ${caps.zoomRange.min}..${caps.zoomRange.max}", state)
            }
        }
        if (patch.torch == true && !caps.supportsTorch) {
            return CameraControlResult.Unsupported("torch", "Torch is unavailable", state)
        }
        patch.stabilizationMode?.let {
            if (it !in caps.stabilizationModes) {
                return CameraControlResult.Unsupported("stabilizationMode", "Stabilization mode is unavailable", state)
            }
        }
        patch.fps?.let { requested ->
            if (caps.fpsRanges.none { requested in it.min..it.max }) {
                return CameraControlResult.Unsupported("fps", "Frame rate is unavailable", state)
            }
        }
        return null
    }

    private fun defaultsFor(caps: CameraCapabilities, previous: CameraSettings): CameraSettings {
        val focusMode = when {
            previous.focusMode in caps.focusModes -> previous.focusMode
            FocusMode.Continuous in caps.focusModes -> FocusMode.Continuous
            FocusMode.Single in caps.focusModes -> FocusMode.Single
            else -> FocusMode.Manual
        }
        val stabilization = when {
            previous.stabilizationMode in caps.stabilizationModes -> previous.stabilizationMode
            StabilizationMode.Video in caps.stabilizationModes -> StabilizationMode.Video
            StabilizationMode.Optical in caps.stabilizationModes -> StabilizationMode.Optical
            else -> StabilizationMode.Off
        }
        return previous.copy(
            exposureMode = if (previous.exposureMode == ExposureMode.Manual && !caps.manualSensor) ExposureMode.Auto else previous.exposureMode,
            iso = previous.iso?.let { caps.isoRange?.clamp(it) },
            shutterNs = previous.shutterNs?.let { caps.shutterRangeNs?.clamp(it) },
            exposureCompensation = caps.exposureCompensationRange?.clamp(previous.exposureCompensation) ?: 0,
            whiteBalanceMode = if (previous.whiteBalanceMode in caps.whiteBalanceModes) previous.whiteBalanceMode else WhiteBalanceMode.Auto,
            whiteBalanceKelvin = if (caps.manualWhiteBalance) previous.whiteBalanceKelvin else null,
            whiteBalanceTint = if (caps.manualWhiteBalance) previous.whiteBalanceTint else 0,
            whiteBalanceLock = previous.whiteBalanceLock && caps.supportsAwbLock,
            focusMode = focusMode,
            focusDistanceDiopters = previous.focusDistanceDiopters?.let { caps.focusDistanceRange?.clamp(it) },
            zoomRatio = caps.zoomRange.clamp(previous.zoomRatio),
            torch = previous.torch && caps.supportsTorch,
            stabilizationMode = stabilization,
            fps = previous.fps?.takeIf { fps -> caps.fpsRanges.any { fps in it.min..it.max } },
        )
    }

    private fun notifyListeners(value: CameraState) {
        listeners.forEach { listener -> runCatching { listener(value) } }
    }
}
