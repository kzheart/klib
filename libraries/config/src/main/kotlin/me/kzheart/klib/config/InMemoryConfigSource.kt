package me.kzheart.klib.config
import java.util.concurrent.atomic.AtomicLong
import me.kzheart.klib.scope.Disposable
class InMemoryConfigSource(private val sourceName: String, @Volatile private var content: String) : ConfigSource {
    private val watchers = ListenerList()
    private val generation = AtomicLong()
    override fun sourceName(): String = sourceName
    override fun prepare(): PreparedConfig {
        val preparedGeneration = generation.get()
        val preparedContent = content
        val document = YamlDocument.parse(sourceName, preparedContent)
        return object : PreparedConfig {
            override fun document(): YamlDocument = document
            override fun commit() {
                if (generation.get() != preparedGeneration) throw ConfigException(
                    "$sourceName:<root>: source changed while configuration was loading")
            }
            override fun revision(): String = preparedContent
        }
    }
    override fun watch(listener: Runnable): Disposable {
        watchers.add(listener)
        return watchers.registration(listener)
    }
    fun update(newContent: String) { content = newContent; generation.incrementAndGet() }
    fun signalChange() { watchers.snapshot().forEach { it.run() } }
}
