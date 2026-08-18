package me.kzheart.klib.data.cache;

/** 在玩家数据加载前访问数据时采用的行为。 */
public enum UnloadedPolicy {
    FAIL_FAST,
    LOAD_ASYNC,
    CREATE_DEFAULT
}
