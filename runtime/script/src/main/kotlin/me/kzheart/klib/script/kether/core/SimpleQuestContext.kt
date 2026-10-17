/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core

import java.util.concurrent.Executor

/** 由 SimpleQuestService 支持、可直接使用的任务上下文。 */
class SimpleQuestContext(service: QuestService<SimpleQuestContext>?, quest: Quest?) :
    AbstractQuestContext<SimpleQuestContext>(service, quest, null) {
    override fun createExecutor(): Executor? = service.executor
}
