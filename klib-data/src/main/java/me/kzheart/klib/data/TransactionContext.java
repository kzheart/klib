package me.kzheart.klib.data;

/** 在单个原子事务中公开的键值数据与结构元数据。 */
public interface TransactionContext extends KeyValueStore {
    int schemaVersion(String schemaName);

    void schemaVersion(String schemaName, int version);

    /**
     * 仅当存储版本仍等于 {@code expectedVersion} 时写入 {@code newVersion}。
     * 在锁定事务中，这可防止两个实例并发应用同一项迁移。
     */
    default boolean compareAndSetSchemaVersion(String schemaName, int expectedVersion, int newVersion) {
        if (schemaVersion(schemaName) != expectedVersion) {
            return false;
        }
        schemaVersion(schemaName, newVersion);
        return true;
    }
}
