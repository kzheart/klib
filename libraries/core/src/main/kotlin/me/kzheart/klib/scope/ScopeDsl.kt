@file:JvmName("ScopeDsl")

package me.kzheart.klib.scope

import me.kzheart.klib.core.kotlin.KlibDsl

/** Runs setup synchronously and repeats it for each successful rebuild. */
public fun scope(name: String, configure: @KlibDsl Scope.() -> Unit): ScopeImpl =
    ScopeImpl.create(name) { it.configure() }

/** Creates a lifecycle-owned child and runs its setup synchronously. */
public fun Scope.childScope(name: String, configure: @KlibDsl Scope.() -> Unit): Scope =
    scope(name) { it.configure() }

public inline fun <reified T : Any> Scope.requireCapability(): T = requireCapability(T::class.java)

public inline fun <reified T : Any> Scope.findCapability(): T? =
    findCapability(T::class.java).orElse(null)

public inline fun <reified T : Any> Scope.registerCapability(capability: T): T =
    registerCapability(T::class.java, capability)
