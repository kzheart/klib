package me.kzheart.example.gather;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 从原 SimpleGather 命令与物品契约复制并锁定的公开行为。 */
public final class GatherContract {
    public static final String ROOT_COMMAND = "simplegather";
    public static final String COMMAND_ALIAS = "sg";
    public static final String USE_PERMISSION = "simplegather.use";
    public static final String ADMIN_PERMISSION = "simplegather.admin";
    public static final String TOOL_TYPE_TAG = "simplegather:type";
    public static final String TOOL_DURABILITY_TAG = "simplegather:durability";
    public static final List<String> COMMANDS = Collections.unmodifiableList(Arrays.asList(
            "help", "reload", "list", "info", "stats", "generate", "spawns", "give", "dump"));

    private GatherContract() {
    }
}
