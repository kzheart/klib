/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** 一个可异步执行的 Kether 动作。 */
public abstract class QuestAction<T> {

    /**
     * 不应直接调用此方法，请参阅 {@link QuestContext.Frame#newFrame(ParsedAction)}。
     */
    public abstract CompletableFuture<T> process(QuestContext.Frame frame);

    /**
     * 为自定义读取器保留未加引号词元的语义。容错块中该词元后若仍有独立动作，
     * 解析器仍报告孤立字面量错误；此方法不放宽已注册动作的语法校验。
     */
    public static QuestAction<String> unquotedLiteral(String token) {
        return new CoreActions.Literal<String>(Objects.requireNonNull(token, "token"), true);
    }

    public static <T> QuestAction<T> noop() {
        return new QuestAction<T>() {
            @Override
            public CompletableFuture<T> process(QuestContext.Frame frame) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public String toString() {
                return "NoOp{}";
            }
        };
    }
}
