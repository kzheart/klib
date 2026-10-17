package me.kzheart.klib.command
import me.kzheart.klib.command.api.CommandRegistration
import me.kzheart.klib.scope.Disposable
internal class CommandRegistrationImpl(private val name: String, private val delegate: Disposable) : CommandRegistration {
    private var disposed = false
    override fun name(): String = name
    @Synchronized override fun dispose() {
        if (!disposed) { disposed = true; delegate.dispose() }
    }
}
