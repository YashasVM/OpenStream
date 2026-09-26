package dev.openstream.app.discovery

/** Reuses a persisted identity or synchronously saves a newly generated one. */
internal fun loadOrCreateInstanceId(
    existingId: String?,
    persist: (String) -> Boolean,
    create: () -> String,
): String {
    existingId?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

    val generatedId = create().trim()
    require(generatedId.isNotEmpty()) { "Generated instance ID must not be blank" }
    check(persist(generatedId)) { "Could not persist phone discovery instance ID" }
    return generatedId
}
