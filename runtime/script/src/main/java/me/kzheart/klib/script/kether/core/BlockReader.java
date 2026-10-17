/*
 * 派生自 TabooLib 的 taboolib.library.kether.BlockReader。
 * Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6
 */
package me.kzheart.klib.script.kether.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 解析具名和匿名 Kether 块。 */
public class BlockReader extends AbstractStringReader {

    protected final Map<String, Quest.Block> blocks;
    protected final QuestService<?> service;
    protected final List<String> namespace;
    private BlockParser parser;
    protected String currentBlock;

    public BlockReader(char[] content, QuestService<?> service, List<String> namespace) {
        super(content);
        this.blocks = new LinkedHashMap<>();
        this.service = service;
        this.namespace = new ArrayList<>(namespace);
    }

    private BlockParser parser() {
        if (parser == null) parser = new BlockParser(this);
        return parser;
    }

    public Quest parse(String id) { return parser().parse(id); }
    public void readBlock() { parser().readBlock(); }
    public void checkLiteral(List<ParsedAction<?>> actions) { parser().checkLiteral(actions); }
    public List<ParsedAction<?>> readActions() { return parser().readActions(namespace); }
    protected ParsedAction<?> readAnonymousAction() { return readAnonymousAction(namespace); }
    protected ParsedAction<?> readAnonymousAction(List<String> activeNamespaces) {
        return parser().readAnonymousAction(activeNamespaces);
    }

    protected SimpleReader newActionReader(QuestService<?> runtime, List<String> namespaces) {
        return new SimpleReader(runtime, this, namespaces);
    }

    protected String nextAnonymousBlockName() {
        return parser().nextAnonymousBlockName();
    }

    protected void processActions(Quest.Block block, List<ParsedAction<?>> actions) {
        if (!actions.isEmpty()) actions.get(0).set(ActionProperties.BLOCK, block.getLabel());
    }

    int lineOf(int index) { return parser().lineOf(index); }
    int columnOf(int index) { return parser().columnOf(index); }

    public Map<String, Quest.Block> getBlocks() { return new LinkedHashMap<>(blocks); }
    public QuestService<?> getService() { return service; }
    public List<String> getNamespace() { return new ArrayList<>(namespace); }
    public String getCurrentBlock() { return currentBlock; }
}
