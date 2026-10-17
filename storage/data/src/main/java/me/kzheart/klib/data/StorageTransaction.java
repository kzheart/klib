package me.kzheart.klib.data;

/** 由存储会话原子执行的工作。 */
@FunctionalInterface
public interface StorageTransaction<T> {
    T execute(TransactionContext context) throws Exception;
}
