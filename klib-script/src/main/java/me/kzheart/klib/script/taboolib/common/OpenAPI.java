package me.kzheart.klib.script.taboolib.common;

import me.kzheart.klib.script.TabooLibKetherInterop;

/**
 * 被 Klib shade relocation 移入业务插件 TabooLib group 的真实 OpenContainer 入口。
 */
public final class OpenAPI {

    private OpenAPI() {
    }

    public static OpenResult call(String name, Object[] data) {
        TabooLibKetherInterop.OpenResult result = TabooLibKetherInterop.call(name, data);
        return result.isSuccessful()
                ? OpenResult.successful(result.getValue())
                : OpenResult.failed();
    }
}
