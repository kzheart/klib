package me.kzheart.klib.remote

/** 自动附带的四项运行环境摘要。 */
class RemoteEnvironment(pluginVersion: String, minecraft: String, java: String, os: String) {
    private val pluginVersion = Texts.requireText(pluginVersion, "pluginVersion")
    private val minecraft = Texts.requireText(minecraft, "minecraft")
    private val java = Texts.requireText(java, "java")
    private val os = Texts.requireText(os, "os")
    fun pluginVersion(): String = pluginVersion
    fun minecraft(): String = minecraft
    fun javaVersion(): String = java
    fun os(): String = os
    fun toMap(): Map<String, Any> = linkedMapOf("plugin_version" to pluginVersion,
        "minecraft" to minecraft, "java" to java, "os" to os)
}
