package dev.openstream.app.camera

internal enum class CameraCaptureOutput {
    Preview,
    Encoder,
}

/** A running camera session may target the encoder alone when its Activity preview detaches. */
internal fun captureOutputKinds(hasPreview: Boolean, hasEncoder: Boolean): Set<CameraCaptureOutput> = buildSet {
    if (hasPreview) add(CameraCaptureOutput.Preview)
    if (hasEncoder) add(CameraCaptureOutput.Encoder)
}
