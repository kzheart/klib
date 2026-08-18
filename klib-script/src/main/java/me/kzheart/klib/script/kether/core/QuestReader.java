/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core;

/** 向动作解析器公开的游标 API。 */
public interface QuestReader {
    char peek();
    char peek(int offset);
    int getIndex();
    void setIndex(int index);
    int getMark();
    boolean hasNext();
    /** 下一个词元前的空白或注释是否跨越源码行。 */
    boolean hasLineBreakBeforeNextToken();
    String nextToken();
    void mark();
    void reset();
    <T> ParsedAction<T> nextAction();
    <T> ParsedAction<T> nextAction(String namespace);
    void expect(String value);

    default int nextInt() { return next(ArgTypes.INT); }
    default long nextLong() { return next(ArgTypes.LONG); }
    default double nextDouble() { return next(ArgTypes.DOUBLE); }
    default <T> T next(ArgType<T> argType) { return argType.read(this); }
    default ParsedAction<?> nextParsedAction() { return next(ArgTypes.ACTION); }
    default ParsedAction<?> nextParsedAction(String namespace) {
        return next(reader -> reader.nextAction(namespace));
    }
}
