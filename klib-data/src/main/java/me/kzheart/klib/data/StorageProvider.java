package me.kzheart.klib.data;

import me.kzheart.klib.scope.Disposable;

import java.util.concurrent.CompletionStage;

/** 为已配置的存储后端创建异步会话。 */
public interface StorageProvider extends Disposable {
    CompletionStage<StorageSession> open();
}
