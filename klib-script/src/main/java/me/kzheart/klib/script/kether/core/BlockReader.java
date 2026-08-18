/*
 * 派生自 TabooLib 的 taboolib.library.kether.BlockReader。
 * Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6
 */
package me.kzheart.klib.script.kether.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** 解析具名和匿名 Kether 块。 */
public class BlockReader extends AbstractStringReader {

    private static final AtomicLong ANONYMOUS_IDS = new AtomicLong();
    protected final Map<String, Quest.Block> blocks;
    protected final QuestService<?> service;
    protected final List<String> namespace;
    private final int[] lineStarts;
    protected String currentBlock;

    public BlockReader(char[] content, QuestService<?> service, List<String> namespace) {
        super(content);
        this.blocks = new LinkedHashMap<>();
        this.service = service;
        this.namespace = new ArrayList<>(namespace);
        this.lineStarts = indexLines(content);
    }

    public Quest parse(String id) {
        while (hasNext()) readBlock();
        return new SimpleQuest(content, blocks, id);
    }

    public void readBlock() {
        expect("def");
        String name = nextToken();
        expect("=");
        currentBlock = name;
        List<ParsedAction<?>> actions = readActions();
        checkLiteral(actions);
        Quest.Block block = new SimpleQuest.SimpleBlock(name, actions);
        processActions(block, actions);
        blocks.put(name, block);
    }

    public void checkLiteral(List<ParsedAction<?>> actions) {
        if (!service.isToleranceParser()) return;
        CoreActions.Literal<?> before = null;
        for (ParsedAction<?> action : actions) {
            if (before != null) {
                throw LocalizedException.of("isolated-literal", String.valueOf(before.getValue()));
            }
            if (action.getAction() instanceof CoreActions.Literal
                    && ((CoreActions.Literal<?>) action.getAction()).isMisspelled()) {
                before = (CoreActions.Literal<?>) action.getAction();
            }
        }
    }

    public List<ParsedAction<?>> readActions() {
        return readActions(namespace);
    }

    private List<ParsedAction<?>> readActions(List<String> activeNamespaces) {
        skipBlank();
        boolean batch = peek() == '{';
        if (batch) skip(1);
        SimpleReader reader = newActionReader(service, activeNamespaces);
        try {
            ArrayList<ParsedAction<?>> actions = new ArrayList<>();
            while ((batch && reader.hasNext()) || actions.isEmpty()) {
                if (batch && reader.peek() == '}') {
                    reader.skip(1);
                    index = reader.index;
                    actions.trimToSize();
                    return actions;
                }
                actions.add(reader.nextAction());
                reader.mark();
            }
            index = reader.index;
            return actions;
        } catch (LocalizedException exception) {
            // 优化 EOF 错误提示
            int from = Math.max(0, Math.min(content.length, reader.getMark()));
            int to = Math.max(from, Math.min(content.length, reader.getIndex()));
            String source = new String(content, from, to - from).trim();
            throw LoadError.BLOCK_ERROR.create(currentBlock, lineOf(from), source).then(exception);
        } catch (RuntimeException exception) {
            throw LoadError.UNHANDLED.create(exception);
        }
    }

    protected ParsedAction<?> readAnonymousAction() {
        return readAnonymousAction(namespace);
    }

    protected ParsedAction<?> readAnonymousAction(List<String> activeNamespaces) {
        String last = currentBlock;
        String name = nextAnonymousBlockName();
        currentBlock = name;
        List<ParsedAction<?>> actions = readActions(activeNamespaces);
        checkLiteral(actions);
        currentBlock = last;
        if (actions.isEmpty()) return ParsedAction.noop();
        ParsedAction<?> head = actions.get(0);
        Quest.Block block = new SimpleQuest.SimpleBlock(name, actions);
        blocks.put(name, block);
        head.set(ActionProperties.BLOCK, block.getLabel());
        return head;
    }

    protected SimpleReader newActionReader(QuestService<?> runtime, List<String> namespaces) {
        return new SimpleReader(runtime, this, namespaces);
    }

    protected String nextAnonymousBlockName() {
        return currentBlock + "_anon_" + ANONYMOUS_IDS.incrementAndGet();
    }

    protected void processActions(Quest.Block block, List<ParsedAction<?>> actions) {
        if (!actions.isEmpty()) actions.get(0).set(ActionProperties.BLOCK, block.getLabel());
    }

    int lineOf(int index) {
        int found = Arrays.binarySearch(lineStarts, Math.max(0, index));
        return found >= 0 ? found + 1 : -found - 1;
    }

    int columnOf(int index) {
        int line = lineOf(index);
        return Math.max(1, index - lineStarts[line - 1] + 1);
    }

    private static int[] indexLines(char[] chars) {
        int count = 1;
        for (char current : chars) {
            if (current == '\n') {
                count++;
            }
        }
        int[] starts = new int[count];
        int line = 1;
        for (int index = 0; index < chars.length; index++) {
            if (chars[index] == '\n') {
                starts[line++] = index + 1;
            }
        }
        return starts;
    }

    public Map<String, Quest.Block> getBlocks() { return new LinkedHashMap<>(blocks); }
    public QuestService<?> getService() { return service; }
    public List<String> getNamespace() { return new ArrayList<>(namespace); }
    public String getCurrentBlock() { return currentBlock; }
}
