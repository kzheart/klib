package me.kzheart.klib.script.taboolib.common

import me.kzheart.klib.script.TabooLibKetherInterop

/** Relocated into the host plugin's TabooLib group as its OpenContainer entry point. */
class OpenAPI private constructor() {
    companion object {
        @JvmStatic fun call(name: String?, data: Array<Any?>?): OpenResult {
            val result = TabooLibKetherInterop.call(name, data)
            return if (result.isSuccessful) OpenResult.successful(result.value) else OpenResult.failed()
        }
    }
}
