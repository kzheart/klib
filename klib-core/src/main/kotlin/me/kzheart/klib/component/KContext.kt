package me.kzheart.klib.component

import me.kzheart.klib.scope.Scope
import me.kzheart.klib.command.api.Commands
import me.kzheart.klib.config.api.Configs
import me.kzheart.klib.event.Events
import me.kzheart.klib.scheduler.Tasks

/** Instance-bound services, with no global current-scope state. */
class KContext(private val owner: Scope) {
    fun scope(): Scope = owner
    fun commands(): Commands = Commands(owner)
    fun configs(): Configs = Configs(owner)
    fun events(): Events = Events(owner)
    fun tasks(): Tasks = Tasks(owner)
    fun components(): Components = Components(owner)
}
