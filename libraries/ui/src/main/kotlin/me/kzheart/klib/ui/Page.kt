package me.kzheart.klib.ui

import java.util.Collections

/** 不可变的分页窗口。 */
class Page<T> internal constructor(values: List<T>, private val index: Int, private val count: Int) {
    private val values = Collections.unmodifiableList(values)
    fun values(): List<T> = values
    fun index(): Int = index
    fun count(): Int = count
    fun hasPrevious(): Boolean = index > 0
    fun hasNext(): Boolean = index + 1 < count
}
