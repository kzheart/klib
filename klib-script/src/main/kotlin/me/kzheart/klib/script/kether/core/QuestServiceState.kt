package me.kzheart.klib.script.kether.core

import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

/** 服务拥有的资源、可变索引和快照边界集中在此，公开门面只保留 Java 调用契约。 */
@JvmSuppressWildcards
internal class QuestServiceState(
    private val service: SimpleQuestService,
    val executor: Executor,
    val asyncExecutor: ScheduledExecutorService,
    private val ownsAsyncExecutor: Boolean,
    val toleranceParser: Boolean
) {
    val registry: QuestRegistry = DefaultRegistry()
    private val loader: QuestLoader = SimpleQuestLoader()
    @Volatile var propertyAccessor: PropertyAccessor? = null
    private val quests = linkedMapOf<String, Quest>()
    private val settings = linkedMapOf<String, MutableMap<String, Any>>()
    private val running = linkedMapOf<String, MutableList<SimpleQuestContext>>()

    fun load(id: String, source: String, namespaces: List<String>): Quest {
        val quest = loader.load(service, id, source.toByteArray(StandardCharsets.UTF_8), namespaces)
        synchronized(quests) { quests[id] = quest }
        return quest
    }
    fun getQuest(id: String): Optional<Quest> = synchronized(quests) { Optional.ofNullable(quests[id]) }
    fun getQuestSettings(id: String): Map<String, Any> = synchronized(settings) {
        Collections.unmodifiableMap(LinkedHashMap(settings.getOrPut(id) { linkedMapOf() }))
    }
    fun getQuests(): Map<String, Quest> = synchronized(quests) { Collections.unmodifiableMap(LinkedHashMap(quests)) }
    fun startQuest(context: SimpleQuestContext) {
        synchronized(running) { running.getOrPut(context.quest.id) { CopyOnWriteArrayList() }.add(context) }
        context.runActions().whenComplete { _, _ -> service.terminateQuest(context) }
    }
    fun terminateQuest(context: SimpleQuestContext) = synchronized(running) {
        val id = context.quest.id
        running[id]?.let { contexts ->
            contexts.remove(context)
            if (contexts.isEmpty()) running.remove(id)
        }
        Unit
    }
    fun getRunningQuests(): Map<String, List<SimpleQuestContext>> = synchronized(running) {
        val snapshot = linkedMapOf<String, List<SimpleQuestContext>>()
        running.forEach { (key, contexts) -> snapshot[key] = Collections.unmodifiableList(ArrayList(contexts)) }
        Collections.unmodifiableMap(snapshot)
    }
    fun getRunningQuests(key: String): List<SimpleQuestContext> = synchronized(running) {
        running[key]?.let { Collections.unmodifiableList(ArrayList(it)) } ?: emptyList()
    }
    fun close() {
        if (ownsAsyncExecutor) asyncExecutor.shutdownNow()
        if (ServiceHolder.getQuestServiceInstance() === service) ServiceHolder.setQuestServiceInstance(null)
    }
    companion object {
        @JvmStatic fun newDaemonScheduler(): ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "klib-kether-core").apply { isDaemon = true }
        }
    }
}
