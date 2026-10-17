/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core

/** Value state with both historical Kotlin property and explicit Java method entry points. */
class ExitStatus(private val running: Boolean, private val waiting: Boolean, private val started: Long) {
    fun isRunning(): Boolean = running
    fun isWaiting(): Boolean = waiting
    fun getStartTime(): Long = started
    @get:JvmName("runningProperty") @get:JvmSynthetic
    val isRunning: Boolean get() = isRunning()
    @get:JvmName("waitingProperty") @get:JvmSynthetic
    val isWaiting: Boolean get() = isWaiting()
    @get:JvmName("startTimeProperty") @get:JvmSynthetic
    val startTime: Long get() = getStartTime()
    override fun equals(other: Any?): Boolean = other is ExitStatus && running == other.running && waiting == other.waiting && started == other.started
    override fun hashCode(): Int = java.util.Objects.hash(running, waiting, started)
    override fun toString(): String = "ExitStatus{running=$running, waiting=$waiting, startTime=$started}"
    companion object {
        private val PAUSED = ExitStatus(true, false, 0)
        @JvmStatic fun success(): ExitStatus = ExitStatus(false, false, 0)
        @JvmStatic fun paused(): ExitStatus = PAUSED
        @JvmStatic fun cooldown(timeout: Long): ExitStatus = ExitStatus(true, true, System.currentTimeMillis() + timeout)
    }
}
