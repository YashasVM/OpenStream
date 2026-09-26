package dev.openstream.app

/** Details selected locally before the OBS control handshake completes. */
internal data class ReservationSelection(
    val sourceInstanceId: String,
    val slotLabel: String = "",
    val bitrateMbps: Int? = null,
    val obsHost: String? = null,
)

/**
 * Owns the phone-side reservation handshake.
 *
 * A pending selection is only a discovery hint: it is advertised with its
 * source id so the matching OBS instance can issue /reserve, but it is not
 * busy and cannot authorize camera controls. Only a successful /reserve
 * promotion creates a confirmed reservation.
 */
internal class ReservationState {
    @Volatile private var pending: ReservationSelection? = null
    @Volatile private var confirmed: ReservationSelection? = null

    val pendingSelection: ReservationSelection?
        get() = pending

    val confirmedReservation: ReservationSelection?
        get() = confirmed

    val pendingSourceInstanceId: String?
        get() = pending?.sourceInstanceId

    val confirmedSourceInstanceId: String?
        get() = confirmed?.sourceInstanceId

    /** A pending selection can be cancelled before OBS confirms ownership. */
    val hasReservationToDisconnect: Boolean
        get() = pending != null || confirmed != null

    /** The id carried in discovery so OBS can initiate the control handshake. */
    val advertisedSourceInstanceId: String?
        get() = confirmed?.sourceInstanceId ?: pending?.sourceInstanceId

    /** Pending selection alone must never make the phone appear occupied. */
    fun isBusy(phoneConnected: Boolean): Boolean = phoneConnected || confirmed != null

    @Synchronized
    fun beginSelection(selection: ReservationSelection): Boolean {
        val current = confirmed
        if (current != null && current.sourceInstanceId != selection.sourceInstanceId) {
            return false
        }
        // A confirmed reservation is already authoritative; do not demote it
        // back to pending when the user taps the same slot again.
        pending = if (current == null) selection else null
        return true
    }

    /** Promotes a matching pending selection, or accepts an OBS-initiated reserve. */
    @Synchronized
    fun confirm(sourceInstanceId: String, slotLabel: String, bitrateMbps: Int?): Boolean {
        val current = confirmed
        if (current != null && current.sourceInstanceId != sourceInstanceId) return false
        val selected = pending
        if (selected != null && selected.sourceInstanceId != sourceInstanceId) return false

        val base = selected ?: current
        confirmed = ReservationSelection(
            sourceInstanceId = sourceInstanceId,
            slotLabel = slotLabel.ifBlank { base?.slotLabel.orEmpty() },
            bitrateMbps = bitrateMbps ?: base?.bitrateMbps,
            obsHost = base?.obsHost,
        )
        pending = null
        return true
    }

    /** Rolls back only the matching unconfirmed selection. */
    @Synchronized
    fun rollbackPending(sourceInstanceId: String): Boolean {
        val selected = pending ?: return true
        if (selected.sourceInstanceId != sourceInstanceId) return false
        pending = null
        return true
    }

    /** Releases confirmed ownership or a matching pending selection. */
    @Synchronized
    fun release(sourceInstanceId: String): Boolean {
        val current = confirmed
        if (current != null && current.sourceInstanceId != sourceInstanceId) return false
        val selected = pending
        if (selected != null && selected.sourceInstanceId != sourceInstanceId) return false
        confirmed = null
        pending = null
        return true
    }

    @Synchronized
    fun clear() {
        confirmed = null
        pending = null
    }
}
