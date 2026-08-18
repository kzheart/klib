/*
 * 派生自 TabooLib 的 taboolib.library.kether.AbstractStringReader。
 * Copyright (c) 2018 Bkm016. Licensed under the MIT License.
 * Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6
 */
package me.kzheart.klib.script.kether.core;

/** Kether 词元读取器使用的基础游标实现。 */
public abstract class AbstractStringReader {

    protected final char[] content;
    protected int index = 0;
    protected int mark = 0;

    protected AbstractStringReader(char[] content) {
        this.content = content;
    }

    protected AbstractStringReader(char[] content, int index, int mark) {
        this.content = content;
        this.index = index;
        this.mark = mark;
    }

    public char peek() {
        if (index < content.length) {
            return content[index];
        } else {
            throw LoadError.EOF.create();
        }
    }

    public char peek(int n) {
        if (index + n < content.length) {
            return content[index + n];
        } else {
            throw LoadError.EOF.create();
        }
    }

    public boolean hasNext() {
        skipBlank();
        return index < content.length;
    }

    public void mark() {
        this.mark = index;
    }

    public void reset() {
        this.index = mark;
    }

    public String nextToken() {
        if (!hasNext()) {
            throw LoadError.EOF.create();
        }
        int begin = index;
        while (index < content.length && !Character.isWhitespace(content[index])) {
            index++;
        }
        return new String(content, begin, index - begin);
    }

    protected void skip(int n) {
        index += n;
    }

    protected void skipBlank() {
        while (index < content.length) {
            if (Character.isWhitespace(content[index])) {
                index++;
            } else if (index + 1 < content.length && content[index] == '/' && content[index + 1] == '/') {
                while (index < content.length && content[index] != '\n' && content[index] != '\r') {
                    index++;
                }
            } else {
                break;
            }
        }
    }

    protected void expect(String value) {
        String element = nextToken();
        if (!element.equals(value)) {
            failExpect(value, element);
        }
    }

    protected void failExpect(String expect, String got) {
        throw LoadError.NOT_MATCH.create(expect, got);
    }

    public char[] getContent() {
        return content.clone();
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public int getMark() {
        return mark;
    }

    public String getRemain() {
        return new String(content, index, content.length - index);
    }
}
