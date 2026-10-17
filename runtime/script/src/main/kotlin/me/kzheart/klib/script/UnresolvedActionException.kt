package me.kzheart.klib.script

/** No container claimed this token; tolerance parsing may treat it as a literal. */
class UnresolvedActionException(action: String) : IllegalArgumentException("No shared Kether action resolved: $action") {
    companion object { private const val serialVersionUID = 1L }
}
