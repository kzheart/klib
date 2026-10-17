/*
 * 派生自 TabooLib 的 taboolib.library.kether.SimpleReader。
 * Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6
 */
package me.kzheart.klib.script.kether.core;

import java.util.ArrayList;
import java.util.List;

/** 通过 {@link QuestRegistry} 解析动作词元。 */
public class SimpleReader extends AbstractStringReader implements QuestReader {

    protected final List<String> namespace;
    protected final QuestService<?> service;
    protected final BlockReader blockParser;

    public SimpleReader(QuestService<?> service, BlockReader reader, List<String> namespace) {
        super(reader.content);
        this.service = service;
        this.blockParser = reader;
        this.index = reader.index;
        this.namespace = new ArrayList<>(namespace);
        if (!this.namespace.contains("kether")) {
            this.namespace.add("kether");
        }
    }

    @Override
    public String source(int begin, int end) {
        if (begin < 0 || end > content.length || begin > end) throw new IndexOutOfBoundsException("source range " + begin + ".." + end);
        return new String(content, begin, end - begin);
    }

    @Override
    public String nextToken() {
        return nextTokenBlock().getToken();
    }

    @Override
    public boolean hasLineBreakBeforeNextToken() {
        return TokenScanner.hasLineBreak(content, index);
    }

    public TokenBlock nextTokenBlock() {
        skipBlank();
        if (index >= content.length) throw LoadError.EOF.create();
        if (peek() != '\'' && peek() != '"') return new TokenBlock(super.nextToken(), false);
        TokenScanner scanner = new TokenScanner(content, index, count -> { skip(count); return index; });
        try { return scanner.quoted(true); } finally { index = scanner.getIndex(); }
    }

    protected ParsedAction<?> nextAnonAction() {
        ParsedAction<?> action = blockParser.readAnonymousAction();
        action.set(ActionProperties.REQUIRE_FRAME, true);
        return action;
    }

    @Override public <T> ParsedAction<T> nextAction() { return nextAction(null); }

    @Override
    public <T> ParsedAction<T> nextAction(String selectedNamespace) {
        return ActionReader.read(this, selectedNamespace, false);
    }

    @Override
    public <T> ParsedAction<T> nextValue() {
        return ActionReader.read(this, null, true);
    }

    @SuppressWarnings("EmptyMethod")
    protected void beforeParse() {
    }

    protected <T> ParsedAction<T> wrap(QuestAction<T> action) { return new ParsedAction<>(action); }
    @Override public void expect(String value) { super.expect(value); }
    public List<String> getNamespace() { return new ArrayList<>(namespace); }
    /** 之后解析的语句也在该命名空间中查找（import 语句）。 */
    public void addNamespace(String value) { if (!namespace.contains(value)) namespace.add(value); }
    /** 不再在该命名空间中查找（release 语句）。 */
    public void removeNamespace(String value) { namespace.remove(value); }
    public QuestService<?> getService() { return service; }
    public BlockReader getBlockParser() { return blockParser; }
}
