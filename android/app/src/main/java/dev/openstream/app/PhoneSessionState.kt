package dev.openstream.app

/** User-visible lifecycle states for the phone's camera session. */
internal enum class PhoneSessionStatus {
    Available,
    Selected,
    Reserved,
    Connecting,
    Live,
    Reconnecting,
    Error,
    Stopped,
}

internal data class PhoneSessionSnapshot(
    val status: PhoneSessionStatus = PhoneSessionStatus.Available,
    val sourceInstanceId: String? = null,
    val generation: Long = 0,
)

/** Pure transition model. A generation changes whenever prior async work is obsolete. */
internal class PhoneSessionState {
    @Volatile
    var snapshot: PhoneSessionSnapshot = PhoneSessionSnapshot()
        private set

    @Synchronized
    fun select(sourceInstanceId: String): PhoneSessionSnapshot {
        if (snapshot.status == PhoneSessionStatus.Stopped) return snapshot
        if (snapshot.sourceInstanceId == sourceInstanceId &&
            snapshot.status in setOf(PhoneSessionStatus.Selected, PhoneSessionStatus.Reserved)
        ) return snapshot
        return advance(PhoneSessionStatus.Selected, sourceInstanceId)
    }

    @Synchronized
    fun reserve(sourceInstanceId: String): Boolean {
        if (snapshot.status == PhoneSessionStatus.Stopped) return false
        val selected = snapshot.sourceInstanceId
        if (selected != null && selected != sourceInstanceId) return false
        if (selected == sourceInstanceId && snapshot.status in setOf(
                PhoneSessionStatus.Reserved,
                PhoneSessionStatus.Connecting,
                PhoneSessionStatus.Live,
                PhoneSessionStatus.Reconnecting,
            )
        ) return true
        advance(PhoneSessionStatus.Reserved, sourceInstanceId)
        return true
    }

    @Synchronized
    fun beginConnection(): Long {
        if (snapshot.status == PhoneSessionStatus.Stopped) return snapshot.generation
        return advance(PhoneSessionStatus.Connecting, snapshot.sourceInstanceId).generation
    }

    @Synchronized
    fun connected(generation: Long): Boolean {
        if (snapshot.status != PhoneSessionStatus.Connecting) return false
        return transitionIfCurrent(generation) { copy(status = PhoneSessionStatus.Live) }
    }

    @Synchronized
    fun connectionLost(generation: Long): Boolean {
        if (snapshot.generation != generation || snapshot.status == PhoneSessionStatus.Stopped) return false
        val nextStatus = if (snapshot.sourceInstanceId == null) {
            PhoneSessionStatus.Available
        } else {
            PhoneSessionStatus.Reconnecting
        }
        advance(nextStatus, snapshot.sourceInstanceId)
        return true
    }

    @Synchronized
    fun failed(generation: Long): Boolean {
        if (!transitionIfCurrent(generation) { copy(status = PhoneSessionStatus.Error) }) return false
        advance(PhoneSessionStatus.Error, snapshot.sourceInstanceId)
        return true
    }

    @Synchronized
    fun timeout(generation: Long): Boolean {
        if (snapshot.generation != generation || snapshot.status == PhoneSessionStatus.Stopped) return false
        snapshot = advance(PhoneSessionStatus.Available, null)
        return true
    }

    /** Disconnect releases OBS ownership while leaving the listener available. */
    @Synchronized
    fun disconnect() {
        if (snapshot.status == PhoneSessionStatus.Stopped) return
        advance(PhoneSessionStatus.Available, null)
    }

    /** Stop is final until an explicit Start action. */
    @Synchronized
    fun stop() {
        if (snapshot.status == PhoneSessionStatus.Stopped) return
        advance(PhoneSessionStatus.Stopped, null)
    }

    @Synchronized
    fun start() {
        if (snapshot.status == PhoneSessionStatus.Stopped) {
            advance(PhoneSessionStatus.Available, null)
        }
    }

    private inline fun transitionIfCurrent(generation: Long, transition: PhoneSessionSnapshot.() -> PhoneSessionSnapshot): Boolean {
        if (snapshot.generation != generation || snapshot.status == PhoneSessionStatus.Stopped) return false
        snapshot = snapshot.transition()
        return true
    }

    private fun advance(status: PhoneSessionStatus, sourceInstanceId: String?): PhoneSessionSnapshot {
        return PhoneSessionSnapshot(status, sourceInstanceId, snapshot.generation + 1).also { snapshot = it }
    }
}
