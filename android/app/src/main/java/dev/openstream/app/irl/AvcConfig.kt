package dev.openstream.app.irl

/** Splits Android csd-0/csd-1, including concatenated 3/4 byte Annex-B NALs. */
data class AvcConfig(val sps: ByteArray, val pps: ByteArray) {
    companion object {
        fun split(vararg csd: ByteArray): AvcConfig? {
            val nals = csd.flatMap { bytes ->
                val starts = mutableListOf<Pair<Int, Int>>()
                var i = 0
                while (i + 2 < bytes.size) {
                    val size = when {
                        i + 3 < bytes.size && bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte() -> 4
                        bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 1.toByte() -> 3
                        else -> 0
                    }
                    if (size > 0) { starts += i to size; i += size } else i++
                }
                if (starts.isEmpty()) listOf(bytes) else starts.mapIndexed { index, (offset, size) ->
                    bytes.copyOfRange(offset + size, starts.getOrNull(index + 1)?.first ?: bytes.size)
                }
            }.filter { it.isNotEmpty() }
            val sps = nals.firstOrNull { it[0].toInt() and 31 == 7 } ?: return null
            val pps = nals.firstOrNull { it[0].toInt() and 31 == 8 } ?: return null
            val start = byteArrayOf(0, 0, 0, 1)
            return AvcConfig(start + sps, start + pps)
        }
    }
}
