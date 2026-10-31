package dev.openstream.irl

internal class NativeMux : AutoCloseable {
    private var handle = create()
    fun write(bytes: ByteArray, pts: Long, flags: Int, audio: Boolean): ByteArray {
        check(handle != 0L)
        return write(handle, bytes, pts, flags, audio)
    }
    override fun close() { if (handle != 0L) { destroy(handle); handle = 0 } }
    private external fun create(): Long
    private external fun destroy(handle: Long)
    private external fun write(handle: Long, bytes: ByteArray, pts: Long, flags: Int, audio: Boolean): ByteArray
    companion object { init { System.loadLibrary("irl_mux") } }
}
