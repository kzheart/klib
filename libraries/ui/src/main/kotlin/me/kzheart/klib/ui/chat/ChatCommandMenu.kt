package me.kzheart.klib.ui.chat

import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.Optional
import java.util.UUID
import java.util.function.Function
import java.util.function.Predicate
import me.kzheart.klib.lang.MessageColor
import me.kzheart.klib.lang.RichText
import me.kzheart.klib.lang.RichTextSegment
import org.bukkit.entity.Player

/** 分组管理页与字段草稿共享权限、校验、确认和输出流程。 */
class ChatCommandMenu(private val panels: BukkitChatPanels) {
    fun open(player: Player, id: String, title: RichText, permission: String, actions: List<Action>) {
        val snapshot = ArrayList(actions)
        val drafts = LinkedHashMap<String, Draft>()
        panels.open(player, ChatPanel.builder(id, title).permission(permission).content { view ->
            val groups = LinkedHashMap<String, MutableList<ChatPanelButton>>()
            for (action in snapshot) {
                if (action.permission.isNotEmpty() && !player.hasPermission(action.permission)) continue
                groups.getOrPut(action.group) { ArrayList() }.add(ChatPanelButton.action(action.id, colored(action.label, MessageColor.GOLD)) { session ->
                    if (action.fields.isEmpty()) execute(session, id, title, action, Draft(player, action))
                    else session.open(form(id, title, action, drafts.getOrPut(action.id) { Draft(player, action) }))
                }.permission(action.permission).hover(RichText.plain(action.description.ifEmpty { action.label })))
            }
            groups.forEach { (group, buttons) -> view.group(group).buttons(3, buttons) }
            if (groups.isEmpty()) view.text(colored("没有可用的管理操作。", MessageColor.GRAY))
        }.build())
    }
    private fun page(id: String, title: RichText, action: Action): ChatPanel.Builder =
        ChatPanel.builder(if (id.length <= 64) id else "menu." + stableId(id), title).permission(action.permission).errorHandler { session, failure ->
            val reason = if (failure is IllegalArgumentException || failure is IllegalStateException) failure.message.toString() else "操作未完成，请查看服务器日志。"
            session.status(colored(reason, MessageColor.RED))
        }
    private fun form(id: String, title: RichText, action: Action, draft: Draft): ChatPanel =
        page("$id.${action.id}", title, action).content { view ->
            view.subtitle(colored(action.label, MessageColor.WHITE)).group("参数")
            for ((index, field) in action.fields.withIndex()) {
                val value = draft.values[index]
                view.buttons(ChatPanelButton.action("field.$index", colored(field.label, MessageColor.GOLD)) { session ->
                    if (field.choices == null) input(session, field, draft, index, false)
                    else session.open(choices(id, title, action, draft, index, ""))
                }.value(colored(if (value.isEmpty()) "未设置" else preview(value, 24), if (value.isEmpty()) MessageColor.GRAY else MessageColor.WHITE))
                    .hover(RichText.plain("${field.label}：${value.ifEmpty { "点击填写" }}\n点击修改；(R) 恢复初始值"))
                    .permission(action.permission).reset { draft.values[index] = field.initial.apply(it.player()) })
            }
            view.footer(ChatPanelButton.action("execute", colored("执行", MessageColor.GREEN)) { session ->
                val invalid = action.fields.indices.firstOrNull { !action.fields[it].valid.test(draft.values[it]) }
                if (invalid != null) session.status(colored("请先填写正确的“${action.fields[invalid].label}”。", MessageColor.YELLOW))
                else execute(session, id, title, action, draft)
            }.permission(action.permission))
        }.build()

    private fun choices(id: String, title: RichText, action: Action, draft: Draft, index: Int, filter: String): ChatPanel {
        val field = action.fields[index]
        return page("$id.${action.id}.field.$index", title, action).content { view ->
            view.subtitle(colored("${action.label} › ${field.label}", MessageColor.WHITE))
            view.text(colored("当前：" + if (draft.values[index].isEmpty()) "未设置" else preview(draft.values[index], 36), MessageColor.GRAY))
            view.group(if (filter.isEmpty()) "可选项" else "筛选 · " + preview(filter, 20))
            val query = filter.lowercase(Locale.ROOT)
            val buttons = ArrayList<ChatPanelButton>()
            for (choice in field.choices!!.apply(view.player())) {
                if (query.isNotEmpty() && query !in "${choice.label} ${choice.value}".lowercase(Locale.ROOT)) continue
                val selected = choice.value == draft.values[index]
                buttons.add(ChatPanelButton.action("choice." + stableId(choice.value),
                    colored(preview(choice.label, 24) + if (selected) " ✓" else "", if (selected) MessageColor.GREEN else MessageColor.GOLD)) { session ->
                    if (!field.valid.test(choice.value)) session.status(colored("该选项已失效，请重新选择。", MessageColor.YELLOW))
                    else {
                        draft.values[index] = choice.value; session.back()
                        session.status(colored("已选择“${preview(choice.label, 28)}”。", MessageColor.GREEN))
                    }
                }.permission(action.permission).hover(RichText.plain("${choice.label}\n${choice.value}")))
            }
            view.buttons(3, buttons)
            if (buttons.isEmpty()) view.text(colored("没有匹配项，可修改筛选或手动输入。", MessageColor.GRAY))
            view.footer(ChatPanelButton.action("search", colored("筛选", MessageColor.GOLD)) { session ->
                session.input(ChatPanelInput.text(64).hint(colored("输入关键词，* 显示全部", MessageColor.GOLD)).current(filter)) { value ->
                    session.open(choices(id, title, action, draft, index, value.trim().takeUnless { it == "*" } ?: ""))
                }
            }.permission(action.permission), ChatPanelButton.action("manual", colored("手动输入", MessageColor.GRAY)) {
                input(it, field, draft, index, true)
            }.permission(action.permission))
        }.build()
    }
    private fun input(session: ChatPanelSession, field: Field, draft: Draft, index: Int, returnToForm: Boolean) {
        session.input(ChatPanelInput.of<String> { value ->
            val trimmed = value.trim()
            if (field.valid.test(trimmed)) Optional.of(trimmed) else Optional.empty()
        }.hint(colored("输入“${field.label}”", MessageColor.GOLD)).current(draft.values[index]).sign(field.sign)) { value ->
            draft.values[index] = value
            if (returnToForm) session.back()
            session.status(colored("已修改“${field.label}”。", MessageColor.GREEN))
        }
    }
    private fun execute(session: ChatPanelSession, id: String, title: RichText, action: Action, draft: Draft) {
        val values = draft.values.toList()
        val command = action.command + if (values.isEmpty()) "" else " " + values.joinToString(" ")
        if (action.confirm) session.open(page("$id.${action.id}.confirm", title, action).once().content { view ->
            view.subtitle(colored("确认 · ${action.label}", MessageColor.WHITE))
            view.text(colored("检查以下内容后执行：", MessageColor.GRAY))
            action.fields.forEachIndexed { index, field -> view.text(colored("${field.label}：${preview(values[index], 64)}", MessageColor.WHITE)) }
            view.buttons(ChatPanelButton.action("confirm", colored("确认执行", MessageColor.GREEN)) { s ->
                s.back(); s.output { s.player().performCommand(command) }
            }.permission(action.permission), ChatPanelButton.action("cancel", colored("取消", MessageColor.GRAY)) { it.back() })
        }.build()) else session.output { session.player().performCommand(command) }
    }
    private class Draft(player: Player, action: Action) { val values = action.fields.mapTo(ArrayList()) { it.initial.apply(player) } }
    class Choice private constructor(internal val label: String, internal val value: String) {
        companion object { @JvmStatic fun of(label: String, value: String): Choice = Choice(label, value) }
    }
    class Field private constructor(internal val label: String) {
        internal var valid = Predicate<String> { it.trim().isNotEmpty() && it.length <= 1024 && '\n' !in it && '\r' !in it }
        internal var initial = Function<Player, String> { "" }
        internal var choices: Function<Player, List<Choice>>? = null
        internal var sign = true
        fun validate(validator: Predicate<String>): Field = apply { valid = valid.and(validator) }
        fun initial(value: String): Field = initial(Function { value })
        fun initial(value: Function<Player, String>): Field = apply { initial = value }
        fun choices(values: Function<Player, List<Choice>>): Field = apply { choices = values }
        fun sign(value: Boolean): Field = apply { sign = value }
        companion object {
            @JvmStatic fun text(label: String): Field = Field(label)
            @JvmStatic fun integer(label: String, minimum: Int, maximum: Int, initial: Int): Field {
                require(minimum <= maximum && initial in minimum..maximum) { "invalid integer bounds" }
                return text(label).initial(initial.toString()).validate { it.toIntOrNull()?.let { parsed -> parsed in minimum..maximum } ?: false }
            }
        }
    }
    class Action private constructor(internal val id: String, internal val group: String, internal val label: String,
                                     internal val command: String, internal val permission: String) {
        internal var description = ""
        internal var fields: List<Field> = emptyList()
        internal var confirm = false
        init {
            ChatPanelButton.checkId(id)
            require(command.isNotEmpty() && command[0] != '/' && '\n' !in command && '\r' !in command) { "invalid command" }
        }
        fun fields(vararg values: Field): Action = apply { fields = ArrayList(values.asList()) }
        fun description(value: String): Action = apply { description = value }
        fun confirm(): Action = apply { confirm = true }
        companion object { @JvmStatic fun of(id: String, group: String, label: String, command: String, permission: String): Action = Action(id, group, label, command, permission) }
    }
    companion object {
        @JvmStatic fun colored(value: String, color: MessageColor?): RichText = RichText(listOf(RichTextSegment(value, color, false, null, null)))
        private fun preview(text: String, length: Int): String = if (text.codePointCount(0, text.length) <= length) text else text.substring(0, text.offsetByCodePoints(0, length)) + "…"
        private fun stableId(value: String): String = UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8)).toString().replace("-", "")
    }
}
