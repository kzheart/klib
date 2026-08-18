package me.kzheart.klib.ui.prompt;

import me.kzheart.klib.scheduler.Ticks;

import java.util.Objects;

/** 基于 {@link ChatPrompt} 构建集成时使用的声明式提示配置。 */
public final class PromptSpec<T> {
    private final PromptParser<T> parser;
    private final Ticks timeout;
    private final String cancelKeyword;
    private final String invalidMessage;
    private final String cancelledMessage;
    private final String timeoutMessage;

    private PromptSpec(Builder<T> builder) {
        this.parser = builder.parser;
        this.timeout = builder.timeout;
        this.cancelKeyword = builder.cancelKeyword;
        this.invalidMessage = builder.invalidMessage;
        this.cancelledMessage = builder.cancelledMessage;
        this.timeoutMessage = builder.timeoutMessage;
    }

    public static <T> Builder<T> builder(PromptParser<T> parser) {
        return new Builder<T>(parser);
    }

    public PromptParser<T> parser() {
        return parser;
    }

    public Ticks timeout() {
        return timeout;
    }

    public String cancelKeyword() {
        return cancelKeyword;
    }

    public String invalidMessage() {
        return invalidMessage;
    }

    public String cancelledMessage() {
        return cancelledMessage;
    }

    public String timeoutMessage() {
        return timeoutMessage;
    }

    public static final class Builder<T> {
        private final PromptParser<T> parser;
        private Ticks timeout = Ticks.seconds(30);
        private String cancelKeyword = "cancel";
        private String invalidMessage = "格式不正确，请重新输入（输入 cancel 取消）";
        private String cancelledMessage = "已取消输入";
        private String timeoutMessage = "输入超时，已取消";

        private Builder(PromptParser<T> parser) {
            this.parser = Objects.requireNonNull(parser, "parser");
        }

        public Builder<T> timeout(Ticks value) {
            timeout = Objects.requireNonNull(value, "timeout");
            return this;
        }

        public Builder<T> cancelKeyword(String value) {
            cancelKeyword = Objects.requireNonNull(value, "cancelKeyword");
            return this;
        }

        public Builder<T> invalidMessage(String value) {
            invalidMessage = Objects.requireNonNull(value, "invalidMessage");
            return this;
        }

        public Builder<T> cancelledMessage(String value) {
            cancelledMessage = Objects.requireNonNull(value, "cancelledMessage");
            return this;
        }

        public Builder<T> timeoutMessage(String value) {
            timeoutMessage = Objects.requireNonNull(value, "timeoutMessage");
            return this;
        }

        public PromptSpec<T> build() {
            return new PromptSpec<T>(this);
        }
    }
}
