package me.kzheart.klib.config
class ConfigValidationException(message: String?) : RuntimeException(message) {
    companion object { private const val serialVersionUID = 1L }
}
