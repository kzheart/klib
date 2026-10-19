package me.kzheart.klib.ui.chat

import me.kzheart.klib.scope.Scope
import org.bukkit.plugin.java.JavaPlugin
import me.kzheart.klib.command.*
import me.kzheart.klib.command.api.CommandHelpStyle
import me.kzheart.klib.lang.*
import me.kzheart.klib.ui.chat.*
import me.kzheart.klib.ui.chat.ChatCommandMenu.*
import me.kzheart.klib.ui.prompt.BukkitChatPrompts
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import java.util.Locale
import java.util.Optional
import java.util.UUID

/** A thin KLib command-panel adapter; permissions and settlement remain in the command routes. */
public class CommandPanels(private val plugin: JavaPlugin, private val scope: Scope) {
    class Control(val group: String,val label: String,val command: String,val permission: String,val confirm: Boolean,fields: List<Field>) {
        constructor(group: String,label: String,command: String,permission: String,confirm: Boolean,vararg fields: Field): this(group,label,command,permission,confirm,java.util.Collections.unmodifiableList(fields.toList()))
        val fields: List<Field> = java.util.Collections.unmodifiableList(fields.toList())
    }
    private val helpFile = File(plugin.dataFolder,"command-help.yml")
    private var modified = Long.MIN_VALUE
    private var style = CommandHelpStyle.preset(CommandHelpStyle.Preset.ELEGANT)
    private val prompts: BukkitChatPrompts
    private val panels: BukkitChatPanels
    init {
        if(!helpFile.exists()) plugin.saveResource("command-help.yml",false)
        helpStyle()
        val bridge = BukkitCommandRegistrar.discover(plugin,plugin.name.lowercase(Locale.ROOT))
        CommandModule.install(scope,{ name,spec,dispatcher ->
            spec.helpStyle(::helpStyle); bridge.register(name,spec,dispatcher)
        },{ call,failure -> call.sender().sendMessage("§c${plugin.name}："+if(failure is IllegalArgumentException || failure is IllegalStateException) failure.message else "操作未完成，请查看本插件日志") })
        prompts = BukkitChatPrompts.install(scope,plugin)
        panels = BukkitChatPanels.install(scope,plugin,plugin.name.lowercase(Locale.ROOT)+"_panel",prompts,ChatPanelOptions.builder().preferSignInput(true).build())
    }
    private fun helpStyle(): CommandHelpStyle {
        if(helpFile.lastModified() != modified) {
            modified = helpFile.lastModified()
            try {
                val yaml = YamlConfiguration().apply { load(helpFile) }
                style = CommandHelpStyles.parse(yaml.getConfigurationSection("style"))
            } catch(failure: Exception) { plugin.logger.warning("命令帮助配置有误，保留原样式：${failure.message}") }
        }
        return style
    }
    fun panels(): BukkitChatPanels = panels
    fun prompts(): BukkitChatPrompts = prompts
    fun open(player: Player,title: String,permission: String,controls: List<Control>) {
        player.closeInventory()
        val actions = controls.map { control ->
            Action.of("cmd."+Integer.toHexString(control.command.hashCode()),control.group,control.label,control.command,control.permission)
                .fields(*control.fields.toTypedArray()).also { if(control.confirm) it.confirm() }
        }
        ChatCommandMenu(panels).open(player,"commands",ChatCommandMenu.colored(title,MessageColor.GOLD),permission,actions)
    }
    companion object {
        @JvmStatic fun world(): Field = Field.text("世界").initial { it.world.name }
            .choices { it.server.worlds.map { world -> Choice.of(world.name, world.name) } }.validate { ' ' !in it && '\t' !in it }
        @JvmStatic fun options(label: String, vararg pairs: String): Field {
            require(pairs.isNotEmpty() && pairs.size % 2 == 0) { "Choices require label/value pairs" }
            val choices = pairs.toList().chunked(2).map { Choice.of(it[0], it[1]) }
            val values = pairs.toList().chunked(2).map { it[1] }
            return Field.text(label).initial(values.first()).choices { choices }.validate { it in values }
        }
        @JvmStatic fun text(value: String): RichText = MessagePipeline(MessageCatalog { Optional.of(value.replace('§','&')) },"",null,MessageRouter { _,_ -> }).render(MessageRecipient.of(value,false),"menu")
        @JvmStatic fun player(uuid: Boolean): Field = Field.text(if(uuid) "玩家 UUID" else "玩家").sign(!uuid)
            .initial { if(uuid) it.uniqueId.toString() else it.name }
            .choices { viewer -> viewer.server.onlinePlayers.filter(viewer::canSee).map { Choice.of(it.name,if(uuid) it.uniqueId.toString() else it.name) } }
            .validate { value -> if(uuid) try { UUID.fromString(value); true } catch(_: IllegalArgumentException) { false } else ' ' !in value && '\t' !in value }
    }
}
