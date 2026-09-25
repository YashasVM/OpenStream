package dev.openstream.app

/** Details selected locally before the OBS control handshake completes. */
internal data class ReservationSelection(
    val sourceInstanceId: String,
    val slotLabel: String = "",
    val bitrateMbps: Int? = null,
    val obsHost: String? = null,
    val reservationToken: String? = null,
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
    // Bounded replay protection for explicit local disconnects. Old owners
    // must remain rejected even after another source has connected.
    private val revokedTokens = LinkedHashMap<String, String>(MAX_REVOKED_TOKENS + 1)

    val pendingSelection: ReservationSelection?
        get() = pending

    val confirmedReservation: ReservationSelection?
        get() = confirmed

    val pendingSourceInstanceId: String?
        get() = pending?.sourceInstanceId

    val confirmedSourceInstanceId: String?
        get() = confirmed?.sourceInstanceId

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
        // An explicit phone selection starts a new ownership attempt. This is
        // the only path that may clear a token revoked by Disconnect, allowing
        // a later OBS Connect for the same source to authenticate again.
        revokedTokens.entries.removeIf { it.value == selection.sourceInstanceId }
        // A confirmed reservation is already authoritative; do not demote it
        // back to pending when the user taps the same slot again.
        pending = if (current == null) selection else null
        return true
    }

    /** Promotes a matching pending selection, or accepts an OBS-initiated reserve. */
    @Synchronized
    fun confirm(
        sourceInstanceId: String,
        slotLabel: String,
        bitrateMbps: Int?,
        reservationToken: String? = null,
    ): Boolean {
        if (reservationToken != null && revokedTokens.containsKey(reservationToken)) return false
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
            reservationToken = reservationToken ?: base?.reservationToken,
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

    /** Explicit user disconnect revokes the current OBS connect attempt. */
    @Synchronized
    fun disconnect() {
        val token = confirmed?.reservationToken ?: pending?.reservationToken
        if (token != null) {
            revokedTokens[token] = confirmed?.sourceInstanceId ?: pending?.sourceInstanceId.orEmpty()
            while (revokedTokens.size > MAX_REVOKED_TOKENS) {
                revokedTokens.entries.iterator().apply { next(); remove() }
            }
        }
        clear()
    }

    private companion object {
        const val MAX_REVOKED_TOKENS = 64
    }
}
