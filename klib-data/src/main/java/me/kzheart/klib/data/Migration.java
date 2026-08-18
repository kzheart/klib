package me.kzheart.klib.data;

/** 一项有序的结构迁移。 */
public final class Migration {
    private final int version;
    private final MigrationAction action;

    public Migration(int version, MigrationAction action) {
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        if (action == null) {
            throw new NullPointerException("action");
        }
        this.version = version;
        this.action = action;
    }

    public int version() {
        return version;
    }

    void apply(TransactionContext context) throws Exception {
        action.apply(context);
    }

    @FunctionalInterface
    public interface MigrationAction {
        void apply(TransactionContext context) throws Exception;
    }
}
