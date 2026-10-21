package dev.openstream.app.stream

import dev.openstream.app.encoder.EncodedAccessUnit

/** Encoders deliver codec config and media through the same output boundary. */
interface EncodedMediaSink {
    fun sendVideoAccessUnit(accessUnit: EncodedAccessUnit): SrtSendResult
    fun sendAudioAccessUnit(accessUnit: EncodedAccessUnit): SrtSendResult
}
