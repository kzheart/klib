package me.kzheart.klib.data.postgresql

import java.time.Duration

class PostgreSqlListenOptions private constructor(builder: Builder) {
    internal val connectTimeout = builder.connectTimeout
    internal val pollTimeout = builder.pollTimeout
    internal val reconnectAttempts = builder.reconnectAttempts
    internal val initialBackoff = builder.initialBackoff
    internal val maxBackoff = builder.maxBackoff
    internal val queueCapacity = builder.queueCapacity
    internal val closeTimeout = builder.closeTimeout
    class Builder internal constructor() {
        internal var connectTimeout = Duration.ofSeconds(10)
        internal var pollTimeout = Duration.ofSeconds(1)
        internal var reconnectAttempts = 5
        internal var initialBackoff = Duration.ofSeconds(1)
        internal var maxBackoff = Duration.ofSeconds(30)
        internal var queueCapacity = 256
        internal var closeTimeout = Duration.ofSeconds(30)
        fun connectTimeout(value: Duration): Builder = apply {
            require(value.toMillis() > 0 && value.seconds < 2147484) { "connectTimeout exceeds pgJDBC seconds range" }; connectTimeout = value
        }
        fun pollTimeout(value: Duration): Builder = apply {
            require(value.toMillis() in 1..Int.MAX_VALUE.toLong()) { "pollTimeout must fit pgJDBC's positive int milliseconds" }; pollTimeout = value
        }
        fun reconnectAttempts(value: Int): Builder = apply { require(value >= 0); reconnectAttempts = value }
        fun backoff(initial: Duration, maximum: Duration): Builder = apply {
            require(initial.toMillis() > 0 && maximum.toMillis() > 0 && maximum >= initial); initialBackoff = initial; maxBackoff = maximum
        }
        fun queueCapacity(value: Int): Builder = apply { require(value > 0); queueCapacity = value }
        fun closeTimeout(value: Duration): Builder = apply { require(value.toMillis() > 0); closeTimeout = value }
        fun build(): PostgreSqlListenOptions = PostgreSqlListenOptions(this)
    }
    companion object {
        @JvmStatic fun builder(): Builder = Builder()
        @JvmStatic fun defaults(): PostgreSqlListenOptions = Builder().build()
    }
}
