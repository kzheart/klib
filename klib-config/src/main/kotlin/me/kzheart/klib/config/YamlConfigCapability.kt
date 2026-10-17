package me.kzheart.klib.config

import java.util.function.Consumer
import me.kzheart.klib.config.api.ConfigDocument
import me.kzheart.klib.scheduler.SchedulerFactory
import me.kzheart.klib.scheduler.Ticks
import me.kzheart.klib.scope.Scope
import me.kzheart.klib.scope.capability.ConfigCapability

class YamlConfigCapability(private val provider: ConfigDocumentProvider, private val mapper: YamlConfigMapper) : ConfigCapability {
    override fun <T> load(owner: Scope, type: Class<T>, path: String): ConfigDocument<T> {
        check(!owner.isClosed) { "Cannot load config into closed scope: ${owner.name()}" }
        val source = provider.open(owner, path) ?: throw ConfigException("$path:<root>: provider returned null source")
        return YamlConfigDocument.open(owner, source, mapper, type, listenerExecutor(owner))
    }
    companion object {
        @JvmStatic fun inMemory(documents: Map<String, String>): YamlConfigCapability {
            val snapshot = LinkedHashMap(documents)
            return YamlConfigCapability({ _, path -> InMemoryConfigSource(path, snapshot[path]
                ?: throw ConfigException("$path:<root>: configuration document was not found")) }, YamlConfigMapper())
        }
        private fun listenerExecutor(owner: Scope): Consumer<Runnable> {
            val factory = owner.findCapability(SchedulerFactory::class.java)
            return if (!factory.isPresent) Consumer { it.run() }
                else Consumer { factory.get().forScope(owner).after(Ticks.of(0L), it) }
        }
    }
}
