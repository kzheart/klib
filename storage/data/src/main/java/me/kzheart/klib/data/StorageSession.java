package me.kzheart.klib.data;

import me.kzheart.klib.scope.Disposable;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** 异步存储会话。阻塞 I/O 始终在提供器执行器中运行。 */
public interface StorageSession extends Disposable {
    CompletionStage<Optional<byte[]>> get(String namespace, String key);

    CompletionStage<Void> put(String namespace, String key, byte[] value);

    CompletionStage<Void> delete(String namespace, String key);

    CompletionStage<Map<String, byte[]>> entries(String namespace);

    <T> CompletionStage<T> transaction(StorageTransaction<T> transaction);
}
