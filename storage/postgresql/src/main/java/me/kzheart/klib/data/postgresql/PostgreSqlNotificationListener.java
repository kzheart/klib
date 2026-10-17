package me.kzheart.klib.data.postgresql;

/** 在专用串行回调线程执行；不调用 Bukkit，也不得阻塞等待本会话关闭。 */
@FunctionalInterface
public interface PostgreSqlNotificationListener {
    void onNotification(PostgreSqlNotification notification);
    default void onListening(long generation) { }
    default void onFailure(Throwable failure, boolean terminal) { }
}
