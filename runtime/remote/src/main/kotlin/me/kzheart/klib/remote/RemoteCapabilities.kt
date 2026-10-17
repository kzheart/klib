package me.kzheart.klib.remote

/** 构建允许的 Remote 能力上限；默认全部关闭。 */
class RemoteCapabilities private constructor(builder: Builder) {
    private val exceptions = builder.exceptionsValue
    private val logs = builder.logsValue
    private val manualIncidents = builder.manualIncidentsValue
    fun exceptions(): Boolean = exceptions
    fun logs(): Boolean = logs
    fun manualIncidents(): Boolean = manualIncidents
    class Builder {
        internal var exceptionsValue = false
        internal var logsValue = false
        internal var manualIncidentsValue = false
        fun exceptions(value: Boolean): Builder = apply { exceptionsValue = value }
        fun logs(value: Boolean): Builder = apply { logsValue = value }
        fun manualIncidents(value: Boolean): Builder = apply { manualIncidentsValue = value }
        fun build(): RemoteCapabilities = RemoteCapabilities(this)
    }
    companion object { @JvmStatic fun builder(): Builder = Builder() }
}
