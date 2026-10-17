package me.kzheart.klib.remote

import java.time.Duration

/** 异常图、日志窗口和扩展采集的有界预算。 */
class IncidentBudget private constructor(builder: Builder) {
    private val throwableDepth = builder.throwableDepthValue
    fun throwableDepth(): Int = throwableDepth
    private val framesPerThrowable = builder.framesPerThrowableValue
    fun framesPerThrowable(): Int = framesPerThrowable
    private val suppressedPerThrowable = builder.suppressedPerThrowableValue
    fun suppressedPerThrowable(): Int = suppressedPerThrowable
    private val throwableNodes = builder.throwableNodesValue
    fun throwableNodes(): Int = throwableNodes
    private val logEntries = builder.logEntriesValue
    fun logEntries(): Int = logEntries
    private val logBytes = builder.logBytesValue
    fun logBytes(): Int = logBytes
    private val breadcrumbs = builder.breadcrumbsValue
    fun breadcrumbs(): Int = breadcrumbs
    private val breadcrumbBytes = builder.breadcrumbBytesValue
    fun breadcrumbBytes(): Int = breadcrumbBytes
    private val maxContributors = builder.maxContributorsValue
    fun maxContributors(): Int = maxContributors
    private val contributorEntries = builder.contributorEntriesValue
    fun contributorEntries(): Int = contributorEntries
    private val contributorBytes = builder.contributorBytesValue
    fun contributorBytes(): Int = contributorBytes
    private val contributorTimeoutMillis = builder.timeoutMillis
    fun contributorTimeoutMillis(): Long = contributorTimeoutMillis
    class Builder {
        internal var throwableDepthValue = 32
        fun throwableDepth(value: Int): Builder = apply { require(value > 0) { "throwableDepth must be positive" }; throwableDepthValue = value }
        internal var framesPerThrowableValue = 256
        fun framesPerThrowable(value: Int): Builder = apply { require(value > 0) { "framesPerThrowable must be positive" }; framesPerThrowableValue = value }
        internal var suppressedPerThrowableValue = 32
        fun suppressedPerThrowable(value: Int): Builder = apply { require(value > 0) { "suppressedPerThrowable must be positive" }; suppressedPerThrowableValue = value }
        internal var throwableNodesValue = 256
        fun throwableNodes(value: Int): Builder = apply { require(value > 0) { "throwableNodes must be positive" }; throwableNodesValue = value }
        internal var logEntriesValue = 128
        fun logEntries(value: Int): Builder = apply { require(value > 0) { "logEntries must be positive" }; logEntriesValue = value }
        internal var logBytesValue = 262144
        fun logBytes(value: Int): Builder = apply { require(value > 0) { "logBytes must be positive" }; logBytesValue = value }
        internal var breadcrumbsValue = 64
        fun breadcrumbs(value: Int): Builder = apply { require(value > 0) { "breadcrumbs must be positive" }; breadcrumbsValue = value }
        internal var breadcrumbBytesValue = 65536
        fun breadcrumbBytes(value: Int): Builder = apply { require(value > 0) { "breadcrumbBytes must be positive" }; breadcrumbBytesValue = value }
        internal var maxContributorsValue = 16
        fun maxContributors(value: Int): Builder = apply { require(value > 0) { "maxContributors must be positive" }; maxContributorsValue = value }
        internal var contributorEntriesValue = 64
        fun contributorEntries(value: Int): Builder = apply { require(value > 0) { "contributorEntries must be positive" }; contributorEntriesValue = value }
        internal var contributorBytesValue = 65536
        fun contributorBytes(value: Int): Builder = apply { require(value > 0) { "contributorBytes must be positive" }; contributorBytesValue = value }
        internal var timeoutMillis = 100L
        fun contributorTimeout(value: Duration): Builder = apply {
            require(!value.isZero && !value.isNegative && value.toMillis() >= 1L) { "contributorTimeout must be positive" }
            timeoutMillis = value.toMillis()
        }
        fun build(): IncidentBudget = IncidentBudget(this)
    }
    companion object {
        @JvmStatic fun defaults(): IncidentBudget = builder().build()
        @JvmStatic fun builder(): Builder = Builder()
    }
}
