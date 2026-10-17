/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core

/** 上下文关闭时用于结束尚未完成的动作结果。 */
class QuestCloseException : IllegalStateException() {
    companion object { private const val serialVersionUID = 1L }
}
