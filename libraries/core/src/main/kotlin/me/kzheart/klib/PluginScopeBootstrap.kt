package me.kzheart.klib

import java.util.function.Consumer
import me.kzheart.klib.scope.Scope
import me.kzheart.klib.scope.ScopeImpl

internal class PluginScopeBootstrap private constructor() {
    companion object {
        @JvmStatic fun create(name: String, setup: Consumer<in Scope>): ScopeImpl = ScopeImpl.create(name, setup)
        @JvmStatic fun rebuild(scope: Scope, failureHandler: Consumer<in Throwable>): Boolean = try {
            scope.rebuild()
            true
        } catch (failure: RuntimeException) { failureHandler.accept(failure); false }
        catch (failure: Error) { failureHandler.accept(failure); throw failure }
    }
}
