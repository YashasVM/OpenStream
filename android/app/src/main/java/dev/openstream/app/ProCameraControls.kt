package dev.openstream.app

import android.app.AlertDialog
import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.openstream.app.camera.Camera2Controller

/** Uses the active lens capabilities every time the panel opens. */
internal fun showProCameraControls(context: Context, camera: Camera2Controller) {
    val caps = camera.controlCapabilities
    val state = camera.controlState
    val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(24, 16, 24, 16)
    }
    fun result(accepted: Boolean) {
        if (!accepted) Toast.makeText(context, "Control unavailable in this camera mode", Toast.LENGTH_SHORT).show()
    }
    fun label(text: String) = TextView(context).also { it.text = text; panel.addView(it) }
    fun toggle(title: String, supported: Boolean, checked: Boolean, change: (Boolean) -> Boolean) {
        panel.addView(Switch(context).apply {
            text = if (supported) title else "$title · unavailable on this lens"
            isEnabled = supported
            isChecked = checked
            minHeight = (48 * context.resources.displayMetrics.density).toInt()
            setOnCheckedChangeListener { _, value -> result(change(value)) }
        })
    }
    label("Exposure compensation · ${caps.exposureStep} EV per step")
    panel.addView(SeekBar(context).apply {
        max = caps.exposureMax - caps.exposureMin
        progress = state.exposureCompensation - caps.exposureMin
        isEnabled = max > 0 && state.exposureTimeNs == null
        contentDescription = "Exposure compensation"
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) {
                if (user) result(camera.setExposureCompensation(value + caps.exposureMin))
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
    })
    toggle("Exposure lock", caps.aeLock, state.aeLocked, camera::setAeLock)
    toggle("White balance lock", caps.awbLock, state.awbLocked, camera::setAwbLock)
    toggle("Focus lock", caps.focusLock, state.focusLocked, camera::setFocusLock)
    label(if (caps.tapFocus) "Tap the preview to focus. Pinch to zoom." else "Tap focus unavailable on this lens.")
    val iso = EditText(context).apply {
        hint = "ISO ${caps.isoMin}–${caps.isoMax}"
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        setText(state.sensitivityIso?.toString() ?: "")
        isEnabled = caps.isoMax > 0
    }
    val exposure = EditText(context).apply {
        hint = "Exposure µs ${caps.exposureTimeMinNs / 1000}–${caps.exposureTimeMaxNs / 1000}"
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        setText(state.exposureTimeNs?.div(1000)?.toString() ?: "")
        isEnabled = caps.exposureTimeMaxNs > 0
    }
    panel.addView(iso); panel.addView(exposure)
    panel.addView(android.widget.Button(context).apply {
        text = "Apply manual exposure"
        isEnabled = caps.isoMax > 0 && caps.exposureTimeMaxNs > 0
        setOnClickListener {
            val sensitivity = iso.text.toString().toIntOrNull()
            val micros = exposure.text.toString().toLongOrNull()
            result(sensitivity != null && micros != null && micros in 1..Long.MAX_VALUE / 1000 &&
                camera.setManualExposure(micros * 1000, sensitivity))
        }
    })
    label("Manual focus · diopters, 0 = infinity")
    panel.addView(SeekBar(context).apply {
        max = 1000
        isEnabled = caps.focusMaxDiopters > 0
        contentDescription = "Manual focus distance"
        progress = if (caps.focusMaxDiopters > 0) ((state.focusDiopters ?: 0f) / caps.focusMaxDiopters * 1000).toInt() else 0
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) {
                if (user) result(camera.setManualFocus(value / 1000f * caps.focusMaxDiopters))
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
    })
    val names = mapOf(CaptureRequest.CONTROL_AWB_MODE_AUTO to "Auto", CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT to "Daylight",
        CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "Cloudy", CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT to "Tungsten",
        CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT to "Fluorescent", CaptureRequest.CONTROL_AWB_MODE_SHADE to "Shade")
    label("White balance presets")
    caps.whiteBalanceModes.filter { names.containsKey(it) }.forEach { mode ->
        panel.addView(android.widget.Button(context).apply {
            text = names.getValue(mode)
            setOnClickListener { result(camera.setWhiteBalance(mode)) }
        })
    }
    AlertDialog.Builder(context).setTitle("Pro camera controls")
        .setView(ScrollView(context).apply { addView(panel) })
        .setNeutralButton("Reset to auto") { _, _ -> camera.resetControls() }
        .setPositiveButton("Done", null).show()
}
