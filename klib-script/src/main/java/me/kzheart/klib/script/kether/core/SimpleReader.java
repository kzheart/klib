/*
 * 派生自 TabooLib 的 taboolib.library.kether.SimpleReader。
 * Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6
 */
package me.kzheart.klib.script.kether.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
        int cursor = index;
        while (cursor < content.length) {
            char current = content[cursor];
            if (current == '\n' || current == '\r') {
                return true;
            }
            if (Character.isWhitespace(current)) {
                cursor++;
                continue;
            }
            if (current == '/' && cursor + 1 < content.length
                    && content[cursor + 1] == '/') {
                return true;
            }
            return false;
        }
        return false;
    }

    public TokenBlock nextTokenBlock() {
        skipBlank();
        if (index >= content.length) throw LoadError.EOF.create();
        switch (peek()) {
            case '"':
                return readDoubleQuoted();
            case '\'':
                return readSingleQuoted();
            default:
                return new TokenBlock(super.nextToken(), false);
        }
    }

    private TokenBlock readDoubleQuoted() {
        int count = 0;
        while (index < content.length && content[index] == '"') {
            count++;
            skip(1);
        }
        int met = 0;
        int cursor;
        for (cursor = index; cursor < content.length; cursor++) {
            if (content[cursor] == '"') met++;
            else if (met >= count) break;
            else met = 0;
        }
        if (met < count) throw LoadError.STRING_NOT_CLOSE.create(count);
        String token = new String(content, index, cursor - count - index);
        index = cursor;
        return new TokenBlock(token, true);
    }

    private TokenBlock readSingleQuoted() {
        skip(1);
        int begin = index;
        while (index < content.length && content[index] != '\'') skip(1);
        if (index >= content.length) throw LoadError.STRING_NOT_CLOSE.create(1);
        String token = new String(content, begin, index - begin);
        skip(1);
        return new TokenBlock(token, true);
    }

    protected ParsedAction<?> nextAnonAction() {
        ParsedAction<?> action = blockParser.readAnonymousAction();
        action.set(ActionProperties.REQUIRE_FRAME, true);
        return action;
    }

    @Override public <T> ParsedAction<T> nextAction() { return nextAction(null); }

    @Override
    public <T> ParsedAction<T> nextAction(String selectedNamespace) {
        return readAction(selectedNamespace, false);
    }

    @Override
    public <T> ParsedAction<T> nextValue() {
        return readAction(null, true);
    }

    @SuppressWarnings("unchecked")
    private <T> ParsedAction<T> readAction(String selectedNamespace, boolean allowLiteral) {
        skipBlank();
        if (index >= content.length) throw LoadError.EOF.create();
        int actionStart = index;
        ParsedAction<T> result;
        switch (peek()) {
            case '{':
                blockParser.index = index;
                ParsedAction<?> anonymous = selectedNamespace == null
                        ? nextAnonAction()
                        : nextAnonAction(selectedNamespace);
                index = blockParser.index;
                result = (ParsedAction<T>) anonymous;
                break;
            case '&': {
                skip(1);
                beforeParse();
                String variable = nextToken();
                int bracket = propertyBracket(variable);
                result = bracket > 0
                        ? (ParsedAction<T>) wrap(new CoreActions.PropertyGet(wrap(new CoreActions.Get<>(variable.substring(0, bracket))),
                                variable.substring(bracket + 1, variable.length() - 1)))
                        : (ParsedAction<T>) wrap(new CoreActions.Get<>(variable));
                break;
            }
            case '*':
                skip(1);
                beforeParse();
                result = (ParsedAction<T>) wrap(new CoreActions.Literal<>(nextToken()));
                break;
            default:
                TokenBlock token = nextTokenBlock();
                String element = token.getToken();
                if (allowLiteral && token.isBlock()) {
                    beforeParse();
                    result = (ParsedAction<T>) wrap(new CoreActions.Literal<>(element));
                    break;
                }
                List<String> domains = selectedNamespaces(selectedNamespace);
                int bracket = token.isBlock() ? -1 : propertyBracket(element);
                if (bracket > 0) {
                    // 原框架的 动作[键] 属性读取简写，如 player[name]。
                    String key = element.substring(bracket + 1, element.length() - 1);
                    String owner = element.substring(0, bracket);
                    Optional<QuestActionParser> ownerParser = service.getRegistry().getParser(owner, domains);
                    beforeParse();
                    if (ownerParser.isPresent()) {
                        result = (ParsedAction<T>) wrap(new CoreActions.PropertyGet(wrap(ownerParser.get().resolve(this)), key));
                        break;
                    }
                    if (allowLiteral || service.isToleranceParser()) {
                        result = (ParsedAction<T>) wrap(new CoreActions.PropertyGet(wrap(new CoreActions.Literal<>(owner, true)), key));
                        break;
                    }
                    throw LoadError.UNKNOWN_ACTION.create(owner);
                }
                Optional<QuestActionParser> parser = service.getRegistry().getParser(element, domains);
                beforeParse();
                if (parser.isPresent()) {
                    result = wrap(parser.get().resolve(this));
                    break;
                }
                if (allowLiteral || service.isToleranceParser()) {
                    result = (ParsedAction<T>) wrap(new CoreActions.Literal<>(element, true));
                    break;
                }
                throw LoadError.UNKNOWN_ACTION.create(element);
        }
        result.set(ActionProperties.LINE, Integer.valueOf(blockParser.lineOf(actionStart)));
        result.set(ActionProperties.COLUMN, Integer.valueOf(blockParser.columnOf(actionStart)));
        return result;
    }

    /** 形如 name[key] 的词元返回左括号位置，否则返回 -1。 */
    private static int propertyBracket(String token) {
        if (token.isEmpty() || token.charAt(token.length() - 1) != ']') return -1;
        int bracket = token.indexOf('[');
        return bracket >= 1 && bracket < token.length() ? bracket : -1;
    }

    private ParsedAction<?> nextAnonAction(String selectedNamespace) {
        ParsedAction<?> action = blockParser.readAnonymousAction(
                selectedNamespaces(selectedNamespace));
        action.set(ActionProperties.REQUIRE_FRAME, true);
        return action;
    }

    private List<String> selectedNamespaces(String selectedNamespace) {
        List<String> domains = new ArrayList<>();
        if (selectedNamespace != null) {
            domains.add(selectedNamespace);
        }
        for (String current : namespace) {
            if (!domains.contains(current)) {
                domains.add(current);
            }
        }
        return domains;
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
