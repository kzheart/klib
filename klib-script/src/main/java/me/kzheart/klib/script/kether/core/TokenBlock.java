/*
 * 派生自 TabooLib 的 taboolib.library.kether.TokenBlock。
 * Copyright (c) 2018 Bkm016. Licensed under the MIT License.
 * Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6
 */
package me.kzheart.klib.script.kether.core;

/**
 * TabooLib
 * taboolib.library.kether.TokenBlock
 *
 * @author 坏黑
 * @since 2022/9/3 16:46
 */
public final class TokenBlock {

    private final String token;
    private final boolean block;
    private final boolean actionBlock;

    public TokenBlock(String token, boolean block) {
        this(token, block, false);
    }

    public TokenBlock(String token, boolean block, boolean actionBlock) {
        this.token = token;
        this.block = block;
        this.actionBlock = actionBlock;
    }

    public String getToken() {
        return token;
    }

    public boolean isBlock() {
        return block;
    }

    public boolean isActionBlock() {
        return actionBlock;
    }
}
