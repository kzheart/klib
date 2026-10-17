package me.kzheart.klib.command
class CommandHelpEntry(private val usage: String, private val description: String,
    private val category: String, private val hover: String) {
    constructor(usage: String, description: String) : this(usage, description, "", "")
    fun usage(): String = usage
    fun description(): String = description
    fun category(): String = category
    fun hover(): String = hover
}
