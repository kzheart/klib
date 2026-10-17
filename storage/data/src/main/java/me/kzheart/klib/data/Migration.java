package me.kzheart.klib.data;

/** 一项有序的结构迁移；嵌套 SAM 保留 Java 平台类型实现边界。 */
public final class Migration {
    private final MigrationState state;

    public Migration(int version, MigrationAction action) {
        state = new MigrationState(version, action);
    }

    public int version() { return state.getVersion(); }

    void apply(TransactionContext context) throws Exception { state.apply(context); }

    @FunctionalInterface
    public interface MigrationAction {
        void apply(TransactionContext context) throws Exception;
    }
}
