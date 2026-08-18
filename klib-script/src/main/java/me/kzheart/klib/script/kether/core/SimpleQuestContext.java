package me.kzheart.klib.script.kether.core;

import java.util.concurrent.Executor;

/** 由 {@link SimpleQuestService} 支持、可直接使用的任务上下文。 */
public final class SimpleQuestContext extends AbstractQuestContext<SimpleQuestContext> {

    public SimpleQuestContext(QuestService<SimpleQuestContext> service, Quest quest) {
        super(service, quest, null);
    }

    @Override
    protected Executor createExecutor() {
        return service.getExecutor();
    }
}
