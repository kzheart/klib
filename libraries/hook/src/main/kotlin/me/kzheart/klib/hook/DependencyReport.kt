package me.kzheart.klib.hook

import java.util.ArrayList
import java.util.Collections

/** 所有可选集成的不可变启动报告。 */
class DependencyReport private constructor(entries: List<Entry>) {
    private val entries: List<Entry> = Collections.unmodifiableList(ArrayList(entries))
    fun entries(): List<Entry> = entries

    fun lines(): List<String> = Collections.unmodifiableList(entries.map { entry ->
        val marker = if (entry.status() == DependencyStatus.AVAILABLE) "✔" else "✖"
        "$marker ${entry.dependency()} ${entry.detail()}"
    })

    /** 一项依赖解析结果。 */
    class Entry internal constructor(private val dependency: String, private val status: DependencyStatus,
                                     private val detail: String) {
        fun dependency(): String = dependency
        fun status(): DependencyStatus = status
        fun detail(): String = detail
    }

    /** 插件启动期间使用的可变报告构建器。 */
    class Builder {
        private val entries = ArrayList<Entry>()
        fun add(hook: Hook<*>): Builder = apply {
            entries.add(Entry(hook.dependency(), hook.status(), hook.detail()))
        }
        fun build(): DependencyReport = DependencyReport(entries)
    }

    companion object {
        @JvmStatic fun builder(): Builder = Builder()
    }
}
