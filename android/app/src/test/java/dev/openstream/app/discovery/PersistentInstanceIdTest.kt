package dev.openstream.app.discovery

import org.junit.Assert.assertEquals
import org.junit.Test

class PersistentInstanceIdTest {
    @Test
    fun identityIsCommittedOnceAndReusedByTheNextAdvertiserRun() {
        var persistedId: String? = null
        var generatedCount = 0

        val firstRunId = loadOrCreateInstanceId(
            existingId = persistedId,
            persist = { id -> persistedId = id; true },
            create = { generatedCount += 1; "phone-instance-1" },
        )
        val secondRunId = loadOrCreateInstanceId(
            existingId = persistedId,
            persist = { id -> persistedId = id; true },
            create = { generatedCount += 1; "phone-instance-2" },
        )

        assertEquals("phone-instance-1", firstRunId)
        assertEquals(firstRunId, secondRunId)
        assertEquals(1, generatedCount)
    }

    @Test(expected = IllegalStateException::class)
    fun failedPersistencePreventsReturningAnUnstableIdentityForAdvertising() {
        loadOrCreateInstanceId(
            existingId = null,
            persist = { false },
            create = { "phone-instance-1" },
        )
    }
}
