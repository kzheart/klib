package me.kzheart.klib.lang

import java.util.Optional

internal class ReloadableMessageCatalog(@Volatile private var delegate: MessageCatalog) : MessageCatalog {
    fun replace(replacement: MessageCatalog) { delegate = replacement }
    override fun find(key: String): Optional<String> = delegate.find(key)
    override fun findLines(key: String): Optional<List<String>> = delegate.findLines(key)
    override fun findRich(key: String): Optional<RichText> = delegate.findRich(key)
    // 一次读取 volatile 引用，整次查询使用同一快照。
    override fun findAny(key: String): Optional<Any> = delegate.findAny(key)
}
