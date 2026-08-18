package me.kzheart.klib.data.cache;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** {@link PlayerDataCache} 使用的异步持久化接口。 */
public interface PlayerDataRepository<T> {
    CompletionStage<T> load(UUID playerId);

    CompletionStage<Void> save(UUID playerId, T value);
}
