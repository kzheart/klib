package me.kzheart.klib.remote

import java.time.Instant
import java.util.Collections

/** Incident 前的有界事实。 */
class Breadcrumb(category: String, message: String, context: Map<String, *>) {
    private val values = Collections.unmodifiableMap(linkedMapOf<String, Any>(
        "occurred_at" to Instant.now().toString(), "category" to Texts.requireText(category, "category"),
        "message" to Texts.requireText(message, "message"), "context" to RemoteEvent.immutableCopy(context)))
    fun toMap(): Map<String, Any> = values
}
