package me.kzheart.klib.data;

/** 表示存储操作无法完成。 */
public final class StorageException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public StorageException(String message) {
        super(message);
    }
}
