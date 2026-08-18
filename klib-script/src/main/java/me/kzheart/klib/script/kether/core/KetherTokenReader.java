/*
 * 词元块处理派生自 TabooLib 的
 * taboolib.library.kether.SimpleReader#nextTokenBlock.
 * Copyright (c) 2018 Bkm016. Licensed under the MIT License.
 * Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6
 */
package me.kzheart.klib.script.kether.core;

import java.util.Objects;

/** 将所有 Kether 解析器共用的无依赖词法子集转换为词元。 */
public final class KetherTokenReader extends AbstractStringReader {

    public KetherTokenReader(String source) {
        super(Objects.requireNonNull(source, "source").toCharArray());
    }

    @Override
    public String nextToken() {
        return nextTokenBlock().getToken();
    }

    public TokenBlock nextTokenBlock() {
        skipBlank();
        if (index >= content.length) {
            throw KetherLexException.endOfInput(index);
        }
        switch (peek()) {
            case '{': {
                int start = index;
                skip(1);
                int begin = index;
                int depth = 1;
                char quote = 0;
                while (index < content.length && depth > 0) {
                    char current = content[index];
                    if (quote != 0) {
                        if (current == quote) {
                            quote = 0;
                        }
                    } else if (current == '\'' || current == '"') {
                        quote = current;
                    } else if (current == '{') {
                        depth++;
                    } else if (current == '}') {
                        depth--;
                    }
                    if (depth > 0) {
                        skip(1);
                    }
                }
                if (depth != 0) {
                    throw KetherLexException.notMatching("}", "end of input", start);
                }
                String token = new String(content, begin, index - begin);
                skip(1);
                return new TokenBlock(token, true, true);
            }
            case '"': {
                int start = index;
                int count = 0;
                while (index < content.length && content[index] == '"') {
                    count++;
                    skip(1);
                }
                int met = 0;
                int cursor;
                for (cursor = index; cursor < content.length; cursor++) {
                    if (content[cursor] == '"') {
                        met++;
                    } else if (met >= count) {
                        break;
                    } else {
                        met = 0;
                    }
                }
                if (met < count) {
                    throw KetherLexException.stringNotClosed(count, start);
                }
                String token = new String(content, index, cursor - count - index);
                index = cursor;
                return new TokenBlock(token, true);
            }
            case '\'': {
                int start = index;
                skip(1);
                int begin = index;
                while (index < content.length && content[index] != '\'') {
                    skip(1);
                }
                if (index >= content.length) {
                    throw KetherLexException.stringNotClosed(1, start);
                }
                String token = new String(content, begin, index - begin);
                skip(1);
                return new TokenBlock(token, true);
            }
            default:
                return new TokenBlock(super.nextToken(), false);
        }
    }
}
