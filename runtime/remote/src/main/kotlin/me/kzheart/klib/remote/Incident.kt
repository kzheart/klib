package me.kzheart.klib.remote

/** 相同 fingerprint 的 Incident 在服务端聚合。 */
class Incident internal constructor(values: Map<String, *>) : RemoteEvent(values)
