package me.kzheart.klib.remote

import java.io.IOException

/** 无法按 /ingest/v1 解码的响应。 */
class RemoteProtocolException internal constructor(message: String) : IOException(message) {
    companion object { private const val serialVersionUID = 1L }
}
