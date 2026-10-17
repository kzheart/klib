package me.kzheart.klib.scope

/** Aggregates lifecycle cleanup failures without replacing the original cause. */
class ScopeLifecycleException : RuntimeException {
    internal constructor(message: String) : super(message)
    internal constructor(message: String, cause: Throwable) : super(message, cause)
    fun addFailure(failure: Throwable) = addSuppressed(failure)
    companion object { private const val serialVersionUID = 1L }
}
