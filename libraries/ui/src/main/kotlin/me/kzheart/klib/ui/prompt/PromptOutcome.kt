package me.kzheart.klib.ui.prompt

import java.util.Optional

/** 不可变的聊天提示结果。 */
class PromptOutcome<T> internal constructor(private val status: PromptStatus, private val value: T?) {
    fun status(): PromptStatus = status
    @Suppress("UNCHECKED_CAST") fun value(): Optional<T> = Optional.ofNullable(value) as Optional<T>
}
