package me.kzheart.klib.data

/** 表示存储操作无法完成。 */
class StorageException : RuntimeException {
    constructor(message: String?, cause: Throwable?) : super(message, cause)
    constructor(message: String?) : super(message)
    private companion object { private const val serialVersionUID = 1L }
}
