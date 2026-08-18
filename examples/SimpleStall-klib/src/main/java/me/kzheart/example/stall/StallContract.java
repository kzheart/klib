package me.kzheart.example.stall;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** SimpleStall 并行迁移测试夹具的稳定行为接口。 */
public final class StallContract {
    public static final String ROOT_COMMAND = "simplestall";
    public static final String COMMAND_ALIAS = "ss";
    public static final String USE_PERMISSION = "simplestall.command";
    public static final String ADMIN_PERMISSION = "simplestall.admin";
    public static final List<String> COMMANDS = Collections.unmodifiableList(Arrays.asList(
            "help", "reload", "manage", "shop", "stall", "fixture"));

    private StallContract() {
    }
}
