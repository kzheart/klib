/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 由 {@link SimpleQuestLoader} 生成的不可变任务实现。 */
public final class SimpleQuest implements Quest {

    private final char[] content;
    private final String id;
    private final Map<String, Block> blocks;

    public SimpleQuest(char[] content, Map<String, Block> blocks, String id) {
        this.content = content.clone();
        this.id = id;
        this.blocks = Collections.unmodifiableMap(new LinkedHashMap<>(blocks));
    }

    public char[] getContent() { return content.clone(); }
    @Override public String getId() { return id; }
    @Override public Optional<Block> getBlock(String label) { return Optional.ofNullable(blocks.get(label)); }
    @Override public Map<String, Block> getBlocks() { return blocks; }

    @Override
    public Optional<Block> blockOf(ParsedAction<?> action) {
        if (action.has(ActionProperties.BLOCK)) {
            Block block = blocks.get(action.get(ActionProperties.BLOCK));
            return block != null && block.getActions().contains(action) ? Optional.of(block) : Optional.empty();
        }
        return blocks.values().stream().filter(block -> block.getActions().contains(action)).findFirst();
    }

    @Override
    public String toString() { return "SimpleQuest{id='" + id + "', blocks=" + blocks + '}'; }

    /** 具名的不可变动作序列。 */
    public static final class SimpleBlock implements Block {
        private final String label;
        private final List<ParsedAction<?>> actions;

        public SimpleBlock(String label, List<ParsedAction<?>> actions) {
            this.label = label;
            this.actions = Collections.unmodifiableList(actions);
        }

        @Override public String getLabel() { return label; }
        @Override public List<ParsedAction<?>> getActions() { return actions; }
        @Override public int indexOf(ParsedAction<?> action) { return actions.indexOf(action); }
        @Override public Optional<ParsedAction<?>> get(int index) {
            return index >= 0 && index < actions.size() ? Optional.of(actions.get(index)) : Optional.empty();
        }
        @Override public String toString() { return "SimpleBlock{label='" + label + "', actions=" + actions + '}'; }
    }
}
