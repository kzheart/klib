package me.kzheart.klib.ui

import java.util.Collections

/** 导航范围受限、结果确定的零基分页。 */
class Paginator<T>(values: List<@JvmSuppressWildcards T>, private val pageSize: Int) {
    private val values = Collections.unmodifiableList(ArrayList(values))
    init { require(pageSize > 0) { "pageSize must be positive" } }
    fun pageCount(): Int = maxOf(1, (values.size + pageSize - 1) / pageSize)
    fun page(requestedIndex: Int): Page<T> {
        val index = requestedIndex.coerceIn(0, pageCount() - 1)
        val from = minOf(values.size, index * pageSize)
        return Page(ArrayList(values.subList(from, minOf(values.size, from + pageSize))), index, pageCount())
    }
}
