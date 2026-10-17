package me.kzheart.klib.remote

class Texts private constructor() {
    companion object {
        @JvmStatic fun requireText(value: String?, name: String): String {
            require(value != null && value.trim { it <= ' ' }.isNotEmpty()) { "$name must not be blank" }
            return value
        }
    }
}
