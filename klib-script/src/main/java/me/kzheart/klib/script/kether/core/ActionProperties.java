/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core;

/** 附加到已解析 Kether 动作的标准元数据。 */
public final class ActionProperties {

    public static final ParsedAction.ActionProperty<String> BLOCK = ParsedAction.ActionProperty.of("block");
    public static final ParsedAction.ActionProperty<Integer> ADDRESS = ParsedAction.ActionProperty.of("address");
    public static final ParsedAction.ActionProperty<Boolean> REQUIRE_FRAME =
            ParsedAction.ActionProperty.of("require_frame");
    public static final ParsedAction.ActionProperty<Integer> LINE =
            ParsedAction.ActionProperty.of("line");
    public static final ParsedAction.ActionProperty<Integer> COLUMN =
            ParsedAction.ActionProperty.of("column");

    private ActionProperties() {
    }
}
