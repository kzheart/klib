package me.kzheart.klib.command.api

import java.util.function.Consumer
import me.kzheart.klib.scope.Disposable
import me.kzheart.klib.scope.Scope

class Commands(private val owner: Scope) {
    fun register(vararg handlers: Any): Disposable =
        owner.requireCapability(CommandCapability::class.java).registerAnnotated(owner, *handlers)
    fun register(name: String, configure: Consumer<in CommandSpec>): CommandRegistration = owner.command(name, configure)
}
