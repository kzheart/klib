/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core

/** 附加到已解析 Kether 动作的标准元数据。 */
class ActionProperties private constructor() {
    companion object {
        @JvmField val BLOCK = ParsedAction.ActionProperty.of<String>("block")
        @JvmField val ADDRESS = ParsedAction.ActionProperty.of<Int>("address")
        @JvmField val REQUIRE_FRAME = ParsedAction.ActionProperty.of<Boolean>("require_frame")
        @JvmField val LINE = ParsedAction.ActionProperty.of<Int>("line")
        @JvmField val COLUMN = ParsedAction.ActionProperty.of<Int>("column")
    }
}
