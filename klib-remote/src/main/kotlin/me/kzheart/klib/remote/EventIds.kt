package me.kzheart.klib.remote

import java.util.UUID

/** 幂等键在事件构造时生成一次，持久化与重试复用。 */
class EventIds private constructor() {
    companion object {
        @JvmStatic fun next(): String = UUID.randomUUID().let {
            java.lang.Long.toHexString(it.mostSignificantBits).padStart(16, '0') +
                java.lang.Long.toHexString(it.leastSignificantBits).padStart(16, '0')
        }
    }
}
