package me.kzheart.klib.remote

import java.time.Instant

/** Keeps snapshot construction behind the Java class's overridable/static API boundary. */
internal object RemoteEventFactory {
    @JvmStatic fun create(type: String, fields: Map<String, *>): RemoteEvent = RemoteEvent(
        LinkedHashMap(snapshot(fields)).apply {
            put("event_id", EventIds.next())
            put("type", Texts.requireText(type, "type"))
            put("occurred_at", Instant.now().toString())
        })

    @JvmStatic fun snapshot(source: Map<String, *>): Map<String, Any?> = JsonValueSnapshot.copy(source)
}
