package me.kzheart.klib.remote

import java.util.logging.Level

/** 运行策略必须与产物能力上限取交集。 */
class RemotePolicy private constructor(builder: Builder) {
    private val paused = builder.pausedValue
    private val exceptions = builder.exceptionsValue
    private val logs = builder.logsValue
    private val manualIncidents = builder.manualIncidentsValue
    private val minimumLevel = builder.levelValue
    private val sampleRate = builder.rateValue
    fun paused(): Boolean = paused
    fun exceptions(): Boolean = exceptions
    fun logs(): Boolean = logs
    fun manualIncidents(): Boolean = manualIncidents
    fun minimumLevel(): Level = minimumLevel
    fun sampleRate(): Int = sampleRate
    fun accepts(level: Level): Boolean = logs && level.intValue() >= minimumLevel.intValue()
    fun restrictTo(capabilities: RemoteCapabilities): RemotePolicy = builder().paused(paused)
        .exceptions(!paused && exceptions && capabilities.exceptions())
        .logs(!paused && logs && capabilities.logs())
        .manualIncidents(!paused && manualIncidents && capabilities.manualIncidents())
        .minimumLevel(minimumLevel).sampleRate(sampleRate).build()
    class Builder {
        internal var pausedValue = false
        internal var exceptionsValue = false
        internal var logsValue = false
        internal var manualIncidentsValue = false
        internal var levelValue: Level = Level.INFO
        internal var rateValue = 100
        fun paused(value: Boolean): Builder = apply { pausedValue = value }
        fun exceptions(value: Boolean): Builder = apply { exceptionsValue = value }
        fun logs(value: Boolean): Builder = apply { logsValue = value }
        fun manualIncidents(value: Boolean): Builder = apply { manualIncidentsValue = value }
        fun minimumLevel(value: Level): Builder = apply { levelValue = value }
        fun sampleRate(value: Int): Builder = apply {
            require(value in 0..100) { "sampleRate must be between 0 and 100" }; rateValue = value
        }
        fun build(): RemotePolicy = RemotePolicy(this)
    }
    companion object {
        @JvmStatic fun builder(): Builder = Builder()
        @JvmStatic fun fromBuild(capabilities: RemoteCapabilities): RemotePolicy = builder()
            .exceptions(capabilities.exceptions()).logs(capabilities.logs())
            .manualIncidents(capabilities.manualIncidents()).build()
    }
}
