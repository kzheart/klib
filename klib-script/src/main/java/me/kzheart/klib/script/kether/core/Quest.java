/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 包含具名块的已解析 Kether 任务。 */
public interface Quest {

    String getId();
    Optional<Block> getBlock(String label);
    Map<String, Block> getBlocks();
    Optional<Block> blockOf(ParsedAction<?> action);

    interface Block {
        String getLabel();
        List<ParsedAction<?>> getActions();
        int indexOf(ParsedAction<?> action);
        Optional<ParsedAction<?>> get(int index);
    }
}
