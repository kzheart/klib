package me.kzheart.klib.compat

/** Primitive capability contract, shared by Java and Kotlin providers. */
fun interface MaterialBridge {
    fun usesLegacyNames(): Boolean
}
