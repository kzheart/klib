package me.kzheart.klib.config
import java.util.function.Consumer
import me.kzheart.klib.config.api.ConfigDocument
import me.kzheart.klib.scope.Disposable
class ConfigErrors private constructor() {
    companion object {
        @JvmStatic fun onReloadFailure(document: ConfigDocument<*>, listener: Consumer<in Throwable>): Disposable =
            (document as? ReloadFailureSource
                ?: throw IllegalArgumentException("Config document does not expose reload failures: ${document.javaClass.name}"))
                .onReloadFailure(listener)
    }
}
