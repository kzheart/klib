package me.kzheart.klib.ui.chat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import me.kzheart.klib.lang.MessageColor;
import me.kzheart.klib.lang.RichText;
import me.kzheart.klib.lang.RichTextSegment;
import org.bukkit.entity.Player;

/** 分组管理入口与参数字段页；业务命令仍以实际玩家身份执行，不绕过原命令的权限与校验。 */
public final class ChatCommandMenu {
    private final BukkitChatPanels panels;

    public ChatCommandMenu(BukkitChatPanels panels) { this.panels = Objects.requireNonNull(panels, "panels"); }

    /** 每次外部打开创建独立草稿；在同一会话内返回与再次进入时保留已填参数。 */
    public void open(Player player, String id, RichText title, String permission, List<Action> actions) {
        final List<Action> snapshot = new ArrayList<Action>(actions);
        final Map<String, Draft> drafts = new LinkedHashMap<String, Draft>();
        panels.open(player, ChatPanel.builder(id, title).permission(permission).content(view -> {
            Map<String, List<ChatPanelButton>> groups = new LinkedHashMap<String, List<ChatPanelButton>>();
            for (Action action : snapshot) {
                if (!action.permission.isEmpty() && !player.hasPermission(action.permission)) continue;
                List<ChatPanelButton> group = groups.get(action.group);
                if (group == null) { group = new ArrayList<ChatPanelButton>(); groups.put(action.group, group); }
                group.add(ChatPanelButton.action(action.id, colored(action.label, MessageColor.GOLD), session -> {
                    if (action.fields.isEmpty()) execute(session, id, title, action, new Draft(player, action));
                    else {
                        Draft draft = drafts.get(action.id);
                        if (draft == null) { draft = new Draft(player, action); drafts.put(action.id, draft); }
                        session.open(form(id, title, action, draft));
                    }
                }).permission(action.permission).hover(RichText.plain(action.description.isEmpty() ? action.label : action.description)));
            }
            for (Map.Entry<String, List<ChatPanelButton>> group : groups.entrySet()) view.group(group.getKey()).buttons(3, group.getValue());
            if (groups.isEmpty()) view.text(colored("没有可用的管理操作。", MessageColor.GRAY));
        }).build());
    }

    private ChatPanel.Builder page(String id, RichText title, Action action) {
        String key = id.length() <= 64 ? id : "menu." + UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        return ChatPanel.builder(key, title).permission(action.permission).errorHandler((session, failure) -> {
            String reason = failure instanceof IllegalArgumentException || failure instanceof IllegalStateException
                    ? String.valueOf(failure.getMessage()) : "操作未完成，请查看服务器日志。";
            session.status(colored(reason, MessageColor.RED));
        });
    }

    private ChatPanel form(String id, RichText title, Action action, Draft draft) {
        return page(id + "." + action.id, title, action).content(view -> {
            view.subtitle(colored(action.label, MessageColor.WHITE));
            view.group("参数");
            for (int i = 0; i < action.fields.size(); i++) {
                final int index = i;
                Field field = action.fields.get(i);
                String value = draft.values.get(i);
                view.buttons(ChatPanelButton.action("field." + i, colored(field.label, MessageColor.GOLD), session -> {
                    if (field.choices == null) input(session, field, draft, index, false);
                    else session.open(choices(id, title, action, draft, index, ""));
                }).value(colored(value.isEmpty() ? "未设置" : preview(value, 24), value.isEmpty() ? MessageColor.GRAY : MessageColor.WHITE))
                        .hover(RichText.plain(field.label + "：" + (value.isEmpty() ? "点击填写" : value) + "\n点击修改；(R) 恢复初始值"))
                        .permission(action.permission).reset(session -> draft.values.set(index, field.initial.apply(session.player()))));
            }
            view.footer(ChatPanelButton.action("execute", colored("执行", MessageColor.GREEN), session -> {
                for (int i = 0; i < action.fields.size(); i++) {
                    Field field = action.fields.get(i);
                    if (!field.valid.test(draft.values.get(i))) {
                        session.status(colored("请先填写正确的“" + field.label + "”。", MessageColor.YELLOW)); return;
                    }
                }
                execute(session, id, title, action, draft);
            }).permission(action.permission));
        }).build();
    }

    private ChatPanel choices(String id, RichText title, Action action, Draft draft, int index, String filter) {
        Field field = action.fields.get(index);
        return page(id + "." + action.id + ".field." + index, title, action).content(view -> {
            view.subtitle(colored(action.label + " › " + field.label, MessageColor.WHITE));
            view.text(colored("当前：" + (draft.values.get(index).isEmpty() ? "未设置" : preview(draft.values.get(index), 36)), MessageColor.GRAY));
            view.group(filter.isEmpty() ? "可选项" : "筛选 · " + preview(filter, 20));
            List<ChatPanelButton> buttons = new ArrayList<ChatPanelButton>();
            String query = filter.toLowerCase(Locale.ROOT);
            for (Choice choice : field.choices.apply(view.player())) {
                if (!query.isEmpty() && !(choice.label + " " + choice.value).toLowerCase(Locale.ROOT).contains(query)) continue;
                String key = "choice." + UUID.nameUUIDFromBytes(choice.value.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
                boolean selected = choice.value.equals(draft.values.get(index));
                buttons.add(ChatPanelButton.action(key, colored(preview(choice.label, 24) + (selected ? " ✓" : ""), selected ? MessageColor.GREEN : MessageColor.GOLD), session -> {
                    if (!field.valid.test(choice.value)) { session.status(colored("该选项已失效，请重新选择。", MessageColor.YELLOW)); return; }
                    draft.values.set(index, choice.value); session.back();
                    session.status(colored("已选择“" + preview(choice.label, 28) + "”。", MessageColor.GREEN));
                }).permission(action.permission).hover(RichText.plain(choice.label + "\n" + choice.value)));
            }
            view.buttons(3, buttons);
            if (buttons.isEmpty()) view.text(colored("没有匹配项，可修改筛选或手动输入。", MessageColor.GRAY));
            view.footer(ChatPanelButton.action("search", colored("筛选", MessageColor.GOLD), session ->
                    session.input(ChatPanelInput.text(64).hint(colored("输入关键词，* 显示全部", MessageColor.GOLD)).current(filter), value ->
                            session.open(choices(id, title, action, draft, index, value.trim().equals("*") ? "" : value.trim())))).permission(action.permission),
                    ChatPanelButton.action("manual", colored("手动输入", MessageColor.GRAY), session -> input(session, field, draft, index, true)).permission(action.permission));
        }).build();
    }

    private void input(ChatPanelSession session, Field field, Draft draft, int index, boolean returnToForm) {
        session.input(ChatPanelInput.of(value -> {
            String trimmed = value.trim();
            return field.valid.test(trimmed) ? Optional.of(trimmed) : Optional.<String>empty();
        }).hint(colored("输入“" + field.label + "”", MessageColor.GOLD)).current(draft.values.get(index)).sign(field.sign), value -> {
            draft.values.set(index, value);
            if (returnToForm) session.back();
            session.status(colored("已修改“" + field.label + "”。", MessageColor.GREEN));
        });
    }

    private void execute(ChatPanelSession session, String id, RichText title, Action action, Draft draft) {
        final List<String> values = new ArrayList<String>(draft.values);
        final String command = action.command + (values.isEmpty() ? "" : " " + String.join(" ", values));
        if (action.confirm) {
            session.open(page(id + "." + action.id + ".confirm", title, action).once().content(view -> {
                view.subtitle(colored("确认 · " + action.label, MessageColor.WHITE));
                view.text(colored("检查以下内容后执行：", MessageColor.GRAY));
                for (int i = 0; i < action.fields.size(); i++) view.text(colored(action.fields.get(i).label + "：" + preview(values.get(i), 64), MessageColor.WHITE));
                view.buttons(ChatPanelButton.action("confirm", colored("确认执行", MessageColor.GREEN), s -> {
                    s.back(); s.output(() -> s.player().performCommand(command));
                }).permission(action.permission), ChatPanelButton.action("cancel", colored("取消", MessageColor.GRAY), ChatPanelSession::back));
            }).build());
        } else session.output(() -> session.player().performCommand(command));
    }

    public static RichText colored(String value, MessageColor color) {
        return new RichText(Collections.singletonList(new RichTextSegment(value, color, false, null, null)));
    }

    private static String preview(String text, int length) {
        if (text.codePointCount(0, text.length()) <= length) return text;
        return text.substring(0, text.offsetByCodePoints(0, length)) + "…";
    }

    private static final class Draft {
        final List<String> values = new ArrayList<String>();
        Draft(Player player, Action action) { for (Field field : action.fields) values.add(field.initial.apply(player)); }
    }

    public static final class Choice {
        final String label;
        final String value;
        private Choice(String label, String value) { this.label = Objects.requireNonNull(label, "label"); this.value = Objects.requireNonNull(value, "value"); }
        public static Choice of(String label, String value) { return new Choice(label, value); }
    }

    public static final class Field {
        final String label;
        private Predicate<String> valid = value -> !value.trim().isEmpty() && value.length() <= 1024 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0;
        private Function<Player, String> initial = player -> "";
        private Function<Player, List<Choice>> choices;
        private boolean sign = true;
        private Field(String label) { this.label = Objects.requireNonNull(label, "label"); }
        public static Field text(String label) { return new Field(label); }
        public Field validate(Predicate<String> validator) { valid = valid.and(Objects.requireNonNull(validator, "validator")); return this; }
        public Field initial(String value) { return initial(player -> value); }
        public Field initial(Function<Player, String> value) { initial = Objects.requireNonNull(value, "initial"); return this; }
        /** 选项在每次重画和点击时重新查询，列表变化后旧按钮不会执行。 */
        public Field choices(Function<Player, List<Choice>> values) { choices = Objects.requireNonNull(values, "choices"); return this; }
        public Field sign(boolean value) { sign = value; return this; }
        public static Field integer(String label, int minimum, int maximum, int initial) {
            if (minimum > maximum || initial < minimum || initial > maximum) throw new IllegalArgumentException("invalid integer bounds");
            return text(label).initial(String.valueOf(initial)).validate(value -> {
                try { int parsed = Integer.parseInt(value); return parsed >= minimum && parsed <= maximum; }
                catch (NumberFormatException ignored) { return false; }
            });
        }
    }

    public static final class Action {
        final String id;
        final String group;
        final String label;
        final String permission;
        final String command;
        private String description = "";
        private List<Field> fields = Collections.emptyList();
        private boolean confirm;
        private Action(String id, String group, String label, String command, String permission) {
            this.id = ChatPanelButton.checkId(id); this.group = Objects.requireNonNull(group, "group"); this.label = Objects.requireNonNull(label, "label");
            this.command = Objects.requireNonNull(command, "command"); this.permission = Objects.requireNonNull(permission, "permission");
            if (command.isEmpty() || command.charAt(0) == '/' || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) throw new IllegalArgumentException("invalid command");
        }
        public static Action of(String id, String group, String label, String command, String permission) { return new Action(id, group, label, command, permission); }
        public Action fields(Field... values) { fields = new ArrayList<Field>(Arrays.asList(values)); return this; }
        public Action description(String value) { description = Objects.requireNonNull(value, "description"); return this; }
        /** 对修改、发放等操作显示一次性确认页；旧确认按钮不能重复提交。 */
        public Action confirm() { confirm = true; return this; }
    }
}
