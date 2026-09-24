package me.kzheart.klib.hook.cost;

/** 某项消耗的描述及玩家当前是否满足，用于渲染 lore 或提示消息。 */
public final class CostLine {
    private final String description;
    private final boolean satisfied;
    private final String error;

    CostLine(String description, boolean satisfied, String error) {
        this.description = description;
        this.satisfied = satisfied;
        this.error = error;
    }

    public String description() {
        return description;
    }

    public boolean satisfied() {
        return satisfied;
    }

    /** 检查过程抛出异常时的信息；正常检查为 {@code null}。 */
    public String error() {
        return error;
    }

    @Override
    public String toString() {
        return (satisfied ? "✔ " : "✘ ") + description;
    }
}
