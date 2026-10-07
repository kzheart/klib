package me.kzheart.klib.ui.chat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import me.kzheart.klib.lang.MessageColor;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Stream;
import me.kzheart.klib.KLogger;
import me.kzheart.klib.lang.RichText;
import me.kzheart.klib.lang.RichTextSegment;
import me.kzheart.klib.lang.TextAction;
import me.kzheart.klib.scheduler.AsyncTask;
import me.kzheart.klib.scheduler.KScheduler;
import me.kzheart.klib.scheduler.SchedulerFactory;
import me.kzheart.klib.scheduler.TaskHandle;
import me.kzheart.klib.scheduler.Ticks;
import me.kzheart.klib.scope.Scope;
import me.kzheart.klib.scope.ScopeImpl;
import me.kzheart.klib.ui.prompt.BukkitChatPrompts;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatPanelsTest {
    @Test void shortInputsPreferSignsWhileLongFieldsKeepChatAndPreferenceCanBeChanged() throws Exception {
        try (Fixture f = new Fixture(ChatPanelOptions.builder().preferSignInput(true).build())) {
            AtomicReference<String> name = new AtomicReference<String>("旧名称");
            f.panels.open(f.player, ChatPanel.builder("field", RichText.plain("字段")).content(view -> view.buttons(
                    ChatPanelButton.action("short", RichText.plain("名称"), s -> s.input(ChatPanelInput.text(32).current(name.get()), name::set)),
                    ChatPanelButton.action("long", RichText.plain("说明"), s -> s.input(ChatPanelInput.text(500).sign(false), name::set)))).build());
            f.click("short"); f.drain();
            assertFalse(f.prompts.hasPrompt(f.id)); assertEquals("旧名称", f.signs.lines[0]);
            f.signs.submit(new String[] {"新名称", "", "", "提示"}); f.drain(); assertEquals("新名称", name.get());
            f.click("@mode"); f.drain(); f.click("short"); f.drain();
            assertTrue(f.prompts.hasPrompt(f.id));
            f.click("@mode"); f.drain(); assertFalse(f.prompts.hasPrompt(f.id), "switching mode replaces the active chat prompt");
            f.signs.submit(new String[] {"切换后", "", "", "提示"}); f.drain(); assertEquals("切换后", name.get());
            f.click("long"); f.drain(); assertTrue(f.prompts.hasPrompt(f.id));
            assertTrue(f.last().contains("输入：聊天"), "mode label reflects the actual long-field input");
            f.click("@mode"); f.drain(); assertTrue(f.last().contains(ChatPanelText.INPUT_CHAT_ONLY.defaultValue().plainText()));
            f.click("@cancel"); f.drain(); f.click("short"); f.drain();
            f.signs.submit(new String[] {"cancel", "", "", "提示"}); f.drain();
            assertEquals("切换后", name.get(), "sign cancellation leaves the field unchanged");
        }
    }

    @Test void returnControlFollowsDeferredCommandOutputAndStaleButtonsStillGiveFeedback() throws Exception {
        try (Fixture f = new Fixture()) {
            f.panels.open(f.player, ChatPanel.builder("main", RichText.plain("管理")).content(view ->
                    view.buttons(ChatPanelButton.action("query", RichText.plain("查询"), session -> session.output(() ->
                            f.scope.after(Ticks.of(1), () -> f.sent.add(new Object[] {f.player, RichText.plain("业务清屏后的查询结果")})))))).build());
            f.click("query"); f.drain();
            assertTrue(f.last().contains("返回继续操作"), "return control follows the command's deferred clear/output");
            assertTrue(f.sentTo(f.player).contains("业务清屏后的查询结果"));
            f.panels.dispatch(f.player, "missing", "missing"); f.drain();
            assertTrue(f.last().contains(ChatPanelText.STALE_PAGE.defaultValue().plainText()));
            assertTrue(f.panels.session(f.player).isPresent(), "an old button restores the current page with feedback");
        }
    }

    @Test void commandFieldsPreserveValuesAndOutputDoesNotCloseTheSession() throws Exception {
        try (Fixture f = new Fixture()) {
            ChatCommandMenu menus = new ChatCommandMenu(f.panels);
            ChatCommandMenu.Field target = ChatCommandMenu.Field.text("玩家").choices(player -> Collections.singletonList(ChatCommandMenu.Choice.of("测试玩家", "tester")));
            menus.open(f.player, "admin", RichText.plain("管理"), "admin", Collections.singletonList(
                    ChatCommandMenu.Action.of("query", "查询", "玩家信息", "info", "admin").fields(target)));
            f.click("query"); f.drain();
            f.click("field.0"); f.drain();
            String choice = "choice." + UUID.nameUUIDFromBytes("tester".getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
            f.click(choice); f.drain();
            assertTrue(f.last().contains("玩家 tester"));
            f.click("execute"); f.drain();
            assertEquals(Collections.singletonList("info tester"), f.commands);
            assertFalse(f.panels.session(f.player).isPresent(), "suspended panels do not interfere with inventory command menus");
            assertTrue(f.last().contains("返回继续操作"));
            f.click("@resume"); f.drain();
            assertTrue(f.panels.session(f.player).isPresent());
            assertTrue(f.last().contains("玩家 tester"));
            f.click("@back"); f.drain(); f.click("query"); f.drain();
            assertTrue(f.last().contains("玩家 tester"), "same menu reuses the field draft");
        }
    }

    @Test void commandConfirmationCannotRepeatAGrantAndIntegerInputIsValidated() throws Exception {
        try (Fixture f = new Fixture()) {
            new ChatCommandMenu(f.panels).open(f.player, "admin", RichText.plain("管理"), "admin", Collections.singletonList(
                    ChatCommandMenu.Action.of("give", "管理", "发放", "give", "admin").fields(ChatCommandMenu.Field.integer("数量", 1, 64, 1)).confirm()));
            f.click("give"); f.drain(); f.click("field.0"); f.drain();
            f.prompts.onChat(f.chat("-5")); f.drain();
            assertTrue(f.panels.session(f.player).get().inputting());
            f.prompts.onChat(f.chat("3")); f.drain();
            assertTrue(f.last().contains("数量 3"));
            f.click("execute"); f.drain(); String[] old = f.command("confirm");
            f.click("confirm"); f.drain();
            f.panels.dispatch(f.player, old[1], old[2]); f.drain();
            assertEquals(Collections.singletonList("give 3"), f.commands);
        }
    }

    @Test void chineseRowsWrapBeforeButtonsAndKeepTheirClickTargets() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger clicked = new AtomicInteger();
            f.panels.open(f.player, ChatPanel.builder("wide", RichText.plain("宽文字")).content(view ->
                    view.buttons(3, ChatPanelButton.action("a", RichText.plain("这是一个很长的中文管理按钮并带有额外说明"), s -> { }),
                            ChatPanelButton.action("b", RichText.plain("这是另一个很长的中文管理按钮并带有额外说明"), s -> clicked.incrementAndGet()),
                            ChatPanelButton.action("c", RichText.plain("第三个按钮"), s -> { }))).build());
            assertEquals(20, f.last().split("\n", -1).length);
            for (String line : f.last().split("\n", -1)) assertFalse(line.contains("额外说明] [这是另"));
            f.click("b"); f.drain(); assertEquals(1, clicked.get());
            RichText rich = new RichText(Collections.singletonList(new RichTextSegment("长文字😀长文字😀", MessageColor.GOLD, false,
                    new TextAction(TextAction.Type.HOVER_TEXT, "完整内容"), new TextAction(TextAction.Type.RUN_COMMAND, "test"))));
            List<RichText> wrapped = ChatPanelLayout.wrap(rich, 20);
            assertEquals(rich.plainText(), wrapped.stream().map(RichText::plainText).collect(Collectors.joining()));
            assertTrue(wrapped.stream().flatMap(row -> row.segments().stream()).allMatch(segment -> segment.click() != null && segment.hover() != null));
        }
    }

    @Test void clickRebuildsFromLiveStateOnTheNextTick() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger speed = new AtomicInteger(1);
            AtomicInteger builds = new AtomicInteger();
            f.panels.open(f.player, ChatPanel.builder("fly", RichText.plain("飞行")).content(view -> {
                builds.incrementAndGet();
                view.group("飞行").buttons(ChatPanelButton.action("speed", RichText.plain("速度"), s -> speed.incrementAndGet())
                        .value(String.valueOf(speed.get())));
            }).build());
            assertTrue(f.last().contains("速度 1"));
            f.click("speed");
            assertEquals(2, speed.get());
            int sent = f.sent.size();
            f.drain();
            assertEquals(sent + 1, f.sent.size(), "redraw happens once on the next tick");
            assertTrue(f.last().contains("速度 2"));
            assertTrue(builds.get() >= 3, "page function is rebuilt for click and redraw");
        }
    }

    @Test void oldButtonsStayUsableAcrossRedraws() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger clicks = new AtomicInteger();
            f.panels.open(f.player, ChatPanel.builder("main", RichText.plain("面板")).content(view ->
                    view.buttons(ChatPanelButton.action("inc", RichText.plain("加一"), s -> clicks.incrementAndGet()))).build());
            String[] old = f.command("inc");
            for (int i = 0; i < 3; i++) { f.panels.dispatch(f.player, old[1], old[2]); f.drain(); }
            assertEquals(3, clicks.get());
            f.panels.dispatch(f.other, old[1], old[2]);
            assertEquals(3, clicks.get(), "another player has no session");
            assertTrue(f.sentTo(f.other).contains(ChatPanelText.EXPIRED.defaultValue().plainText()));
        }
    }

    @Test void wholePageIsOneMessageWithFixedLinesAndSeveralButtonsPerRow() throws Exception {
        try (Fixture f = new Fixture()) {
            f.panels.open(f.player, ChatPanel.builder("grid", RichText.plain("网格")).perLine(3).content(view -> {
                List<ChatPanelButton> buttons = new ArrayList<ChatPanelButton>();
                for (int i = 0; i < 7; i++) buttons.add(ChatPanelButton.action("b" + i, RichText.plain("按钮" + i), s -> { }).hover(RichText.plain("说明" + i)));
                view.group("分组").buttons(buttons);
            }).build());
            assertEquals(1, f.sent.size());
            String[] lines = f.last().split("\n", -1);
            assertEquals(20, lines.length);
            assertTrue(lines[3].contains("[按钮0]") && lines[3].contains("[按钮2]") && !lines[3].contains("按钮3"));
            assertTrue(f.segments().anyMatch(s -> s.hover() != null && s.hover().value().contains("说明4")));
            assertTrue(f.last().indexOf("[关闭]") < f.last().indexOf(ChatPanelText.EXPAND.defaultValue().plainText()), "navigation stays beside content instead of at the bottom");
        }
    }

    @Test void staleButtonsAndPagesAreAnnouncedAndRedrawn() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicBoolean present = new AtomicBoolean(true);
            AtomicInteger runs = new AtomicInteger();
            f.panels.open(f.player, ChatPanel.builder("list", RichText.plain("列表")).content(view -> {
                if (present.get()) view.buttons(ChatPanelButton.action("line.1", RichText.plain("删除"), s -> runs.incrementAndGet()));
            }).build());
            String[] old = f.command("line.1");
            present.set(false);
            f.panels.dispatch(f.player, old[1], old[2]);
            f.drain();
            assertEquals(0, runs.get());
            assertTrue(f.statusLine().contains(ChatPanelText.STALE_BUTTON.defaultValue().plainText()));
            f.panels.dispatch(f.player, "missing", "x");
            f.drain();
            assertTrue(f.statusLine().contains(ChatPanelText.STALE_PAGE.defaultValue().plainText()));
        }
    }

    @Test void resultStatusStaysInsideThePanel() throws Exception {
        try (Fixture f = new Fixture()) {
            f.panels.open(f.player, ChatPanel.builder("save", RichText.plain("保存")).content(view ->
                    view.buttons(ChatPanelButton.action("save", RichText.plain("保存"), s -> s.status("已保存")))).build());
            f.click("save");
            f.drain();
            assertTrue(f.statusLine().contains("已保存"));
            assertTrue(f.panels.notice(f.player, RichText.plain("外部结果")));
            f.drain();
            assertTrue(f.statusLine().contains("外部结果"));
        }
    }

    @Test void permissionAndGuardAreRecheckedAtClickTime() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger actions = new AtomicInteger();
            AtomicBoolean unchanged = new AtomicBoolean(true);
            f.panels.open(f.player, ChatPanel.builder("item", RichText.plain("物品")).guard(p -> unchanged.get()).content(view ->
                    view.buttons(ChatPanelButton.action("edit", RichText.plain("修改"), s -> actions.incrementAndGet()).permission("edit"))).build());
            String[] token = f.command("edit");
            f.permitted = false;
            f.panels.dispatch(f.player, token[1], token[2]);
            f.drain();
            assertEquals(0, actions.get());
            assertTrue(f.statusLine().contains(ChatPanelText.NO_PERMISSION.defaultValue().plainText()));
            assertFalse(f.last().contains("修改"), "buttons without permission are hidden");
            f.permitted = true;
            unchanged.set(false);
            f.panels.dispatch(f.player, token[1], token[2]);
            assertEquals(0, actions.get());
            assertTrue(f.last().contains(ChatPanelText.UNAVAILABLE.defaultValue().plainText()));
            assertFalse(f.panels.session(f.player).isPresent());
        }
    }

    @Test void inputKeepsThePanelVisibleAndReturnsToTheOriginPage() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicReference<String> name = new AtomicReference<String>("旧名");
            ChatPanelSession session = f.panels.open(f.player, ChatPanel.builder("name", RichText.plain("名称")).content(view ->
                    view.line(RichText.plain("名称：" + name.get()), ChatPanelButton.action("edit", RichText.plain("修改"), s ->
                            s.input(ChatPanelInput.text(32).hint(RichText.plain("输入新名称")).current(name.get()), name::set)))).build()).get();
            f.click("edit");
            f.drain();
            assertTrue(session.inputting());
            assertTrue(f.statusLine().contains("输入新名称"));
            assertTrue(f.segments().anyMatch(s -> s.click() != null && s.click().type() == TextAction.Type.SUGGEST_COMMAND && s.click().value().equals("旧名")));
            assertTrue(f.last().contains("名称：旧名"), "page body stays visible during input");
            Thread chat = new Thread(() -> f.prompts.onChat(f.chat("新名")));
            chat.start(); chat.join();
            f.drain();
            assertEquals("新名", name.get());
            assertFalse(session.inputting());
            assertEquals("name", session.panelId());
            assertTrue(f.last().contains("名称：新名"));
        }
    }

    @Test void cancelButtonEndsInputWithAStatus() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger cancelled = new AtomicInteger();
            ChatPanelSession session = f.panels.open(f.player, ChatPanel.builder("name", RichText.plain("名称")).content(view ->
                    view.buttons(ChatPanelButton.action("edit", RichText.plain("修改"), s ->
                            s.input(ChatPanelInput.text(8), value -> fail("not submitted"), cancelled::incrementAndGet)))).build()).get();
            f.click("edit");
            f.drain();
            f.click("@cancel");
            f.drain();
            assertEquals(1, cancelled.get());
            assertFalse(session.inputting());
            assertTrue(f.statusLine().contains(ChatPanelText.INPUT_CANCELLED.defaultValue().plainText()));
        }
    }

    @Test void inputCompletionRejectsAChangedTarget() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicBoolean unchanged = new AtomicBoolean(true);
            AtomicInteger applied = new AtomicInteger();
            ChatPanelSession session = f.panels.open(f.player, ChatPanel.builder("field", RichText.plain("字段")).guard(p -> unchanged.get()).build()).get();
            session.input(ChatPanelInput.text(8), value -> applied.incrementAndGet());
            Thread chat = new Thread(() -> f.prompts.onChat(f.chat("新值")));
            chat.start(); chat.join();
            unchanged.set(false);
            f.drain();
            assertEquals(0, applied.get());
            assertTrue(session.isClosed());
        }
    }

    @Test void reopeningCancelsOnlyThePanelsOwnInput() throws Exception {
        try (Fixture f = new Fixture()) {
            ChatPanel model = ChatPanel.builder("panel", RichText.plain("面板")).build();
            ChatPanelSession session = f.panels.open(f.player, model).get();
            session.input(ChatPanelInput.text(8), value -> fail("old input invoked"));
            f.panels.open(f.player, model);
            f.drain();
            assertFalse(f.prompts.hasPrompt(f.id));
            assertFalse(session.inputting());
        }
    }

    @Test void oncePagesCannotBeConfirmedTwice() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger deleted = new AtomicInteger();
            ChatPanel confirm = ChatPanel.builder("confirm", RichText.plain("确认")).once().content(view ->
                    view.buttons(ChatPanelButton.action("yes", RichText.plain("确认"), s -> deleted.incrementAndGet()))).build();
            f.panels.open(f.player, ChatPanel.builder("main", RichText.plain("主页")).content(view ->
                    view.buttons(ChatPanelButton.action("delete", RichText.plain("删除"), s -> s.open(confirm)))).build());
            f.click("delete");
            f.drain();
            String[] yes = f.command("yes");
            f.panels.dispatch(f.player, yes[1], yes[2]);
            f.drain();
            assertTrue(f.last().contains("主页"), "a used confirmation returns to the previous page");
            f.panels.dispatch(f.player, yes[1], yes[2]);
            f.drain();
            assertEquals(1, deleted.get());
            assertTrue(f.statusLine().contains(ChatPanelText.STALE_PAGE.defaultValue().plainText()));
        }
    }

    @Test void navigationKeepsAReturnPath() throws Exception {
        try (Fixture f = new Fixture()) {
            ChatPanel child = ChatPanel.builder("child", RichText.plain("子页")).build();
            ChatPanelSession session = f.panels.open(f.player, ChatPanel.builder("root", RichText.plain("根页")).content(view ->
                    view.buttons(ChatPanelButton.action("enter", RichText.plain("进入"), s -> s.open(child)))).build()).get();
            String[] enter = f.command("enter");
            f.click("enter");
            f.drain();
            assertEquals("child", session.panelId());
            assertTrue(f.last().contains("[返回]"));
            f.click("@back");
            f.drain();
            assertEquals("root", session.panelId());
            f.panels.dispatch(f.player, enter[1], enter[2]);
            f.drain();
            assertEquals("child", session.panelId(), "root buttons from older messages keep working");
        }
    }

    @Test void groupsArePaginatedAndPageButtonsWork() throws Exception {
        try (Fixture f = new Fixture()) {
            ChatPanelSession session = f.panels.open(f.player, ChatPanel.builder("big", RichText.plain("大页")).content(view -> {
                for (int g = 0; g < 6; g++) {
                    view.group("组" + g);
                    for (int i = 0; i < 3; i++) view.text(RichText.plain("组" + g + "行" + i));
                }
            }).build()).get();
            assertTrue(f.last().contains("组0行2") && !f.last().contains("组4行0"));
            f.click("@page.1");
            f.drain();
            assertEquals(1, session.page());
            assertTrue(f.last().contains("组4") && f.last().split("\n", -1).length == 20);
        }
    }

    @Test void resetButtonsRouteSeparately() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger value = new AtomicInteger(5);
            f.panels.open(f.player, ChatPanel.builder("meta", RichText.plain("数据")).content(view ->
                    view.buttons(ChatPanelButton.action("size", RichText.plain("大小"), s -> value.incrementAndGet())
                            .value(String.valueOf(value.get())).reset(s -> value.set(0)))).build());
            assertTrue(f.last().contains("[大小 5 (R)]"));
            f.click("size!r");
            f.drain();
            assertTrue(f.last().contains("[大小 0 (R)]"));
        }
    }

    @Test void signInputPrefillsAndSubmitsWhenPreferred() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicReference<String> name = new AtomicReference<String>("abcdefghijklmnopq");
            ChatPanelSession session = f.panels.open(f.player, ChatPanel.builder("name", RichText.plain("名称")).content(view ->
                    view.buttons(ChatPanelButton.action("edit", RichText.plain("修改"), s ->
                            s.input(ChatPanelInput.text(64).current(name.get()), name::set)))).build()).get();
            assertTrue(f.last().contains("输入：聊天"));
            f.click("@mode");
            f.drain();
            assertTrue(f.last().contains("输入：告示牌"));
            f.click("edit");
            assertEquals("abcdefghijklmnopq", f.signs.lines[0] + f.signs.lines[1] + f.signs.lines[2]);
            assertEquals(ChatPanelText.SIGN_LINE.defaultValue().plainText(), f.signs.lines[3]);
            assertFalse(f.prompts.hasPrompt(f.id));
            f.signs.submit(new String[] {"新", "名称", "", "忽略"});
            f.drain();
            assertEquals("新名称", name.get());
            assertFalse(session.inputting());
        }
    }

    @Test void closeClearsTheScreenAndLaterClicksAreAnnounced() throws Exception {
        try (Fixture f = new Fixture()) {
            f.panels.open(f.player, ChatPanel.builder("main", RichText.plain("面板")).content(view ->
                    view.buttons(ChatPanelButton.action("x", RichText.plain("执行"), s -> { }))).build());
            String[] old = f.command("x");
            f.click("@close");
            String[] lines = f.last().split("\n", -1);
            assertEquals(20, lines.length);
            assertTrue(lines[19].contains(ChatPanelText.CLOSED.defaultValue().plainText()));
            f.panels.dispatch(f.player, old[1], old[2]);
            assertTrue(f.last().contains(ChatPanelText.EXPIRED.defaultValue().plainText()));
        }
    }

    @Test void idleExpiryAndScopeCloseRevokeTheSession() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger actions = new AtomicInteger();
            ChatPanel panel = ChatPanel.builder("main", RichText.plain("面板")).content(view ->
                    view.buttons(ChatPanelButton.action("x", RichText.plain("执行"), s -> actions.incrementAndGet()))).build();
            f.panels.open(f.player, panel);
            String[] old = f.command("x");
            long idle = ChatPanelOptions.defaults().idleMillis();
            f.now = idle - 1;
            f.panels.dispatch(f.player, old[1], old[2]);
            assertEquals(1, actions.get(), "activity renews the idle timer");
            f.now += idle;
            f.panels.dispatch(f.player, old[1], old[2]);
            assertEquals(1, actions.get());
            assertTrue(f.last().contains(ChatPanelText.EXPIRED.defaultValue().plainText()));
            f.panels.open(f.player, panel);
            f.scope.close();
            f.panels.dispatch(f.player, old[1], old[2]);
            assertEquals(1, actions.get());
        }
    }

    @Test void duplicateButtonIdsAreRejected() throws Exception {
        try (Fixture f = new Fixture()) {
            f.panels.open(f.player, ChatPanel.builder("dup", RichText.plain("重复")).content(view -> view.buttons(
                    ChatPanelButton.action("a", RichText.plain("1"), s -> { }), ChatPanelButton.action("a", RichText.plain("2"), s -> { }))).build());
            assertTrue(f.last().contains(ChatPanelText.FAILED.defaultValue().plainText()));
            assertThrows(IllegalArgumentException.class, () -> ChatPanelButton.action("带 空格", RichText.plain("x"), s -> { }));
        }
    }

    @Test void statusSetInsideThePageFunctionDoesNotLoopRedraws() throws Exception {
        try (Fixture f = new Fixture()) {
            f.panels.open(f.player, ChatPanel.builder("loop", RichText.plain("循环")).content(view -> view.session().refresh()).build());
            f.drain();
            assertEquals(1, f.sent.size());
        }
    }

    @Test void nestedInputKeepsTheOriginatingButtonPermission() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicReference<String> result = new AtomicReference<String>();
            f.panels.open(f.player, ChatPanel.builder("nbt", RichText.plain("NBT")).content(view ->
                    view.buttons(ChatPanelButton.action("add", RichText.plain("新增"), s -> s.input(ChatPanelInput.text(8), key ->
                            s.input(ChatPanelInput.text(8), value -> result.set(key + "=" + value)))).permission("nbt"))).build());
            f.click("add");
            Thread first = new Thread(() -> f.prompts.onChat(f.chat("k")));
            first.start(); first.join();
            f.drain();
            f.permitted = false;
            Thread second = new Thread(() -> f.prompts.onChat(f.chat("v")));
            second.start(); second.join();
            f.drain();
            assertNull(result.get(), "permission revoked between nested inputs is enforced");
            assertTrue(f.statusLine().contains(ChatPanelText.NO_PERMISSION.defaultValue().plainText()));
        }
    }

    @Test void reopeningInsideAnActionSendsOnceAndKeepsTheNotice() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger count = new AtomicInteger();
            AtomicReference<Consumer<Player>> reopen = new AtomicReference<Consumer<Player>>();
            reopen.set(player -> f.panels.open(player, ChatPanel.builder("menu", RichText.plain("菜单")).content(view ->
                    view.buttons(ChatPanelButton.action("inc", RichText.plain("计数"), s -> {
                        count.incrementAndGet();
                        f.panels.notice(s.player(), RichText.plain("已加一"));
                        reopen.get().accept(s.player());
                    }).value(String.valueOf(count.get())))).build()));
            reopen.get().accept(f.player);
            int sent = f.sent.size();
            f.click("inc");
            f.drain();
            assertEquals(sent + 1, f.sent.size(), "the action already rendered the page");
            assertTrue(f.last().contains("计数 1"));
            assertTrue(f.statusLine().contains("已加一"));
        }
    }

    @Test void offMainThreadOperationsAreRejected() throws Exception {
        try (Fixture f = new Fixture()) {
            f.primary = false;
            assertThrows(IllegalStateException.class, () -> f.panels.open(f.player, ChatPanel.builder("p", RichText.plain("面板")).build()));
            f.primary = true;
        }
    }

    static final class FakeSigns implements ChatPanelSignInput {
        String[] lines;
        Consumer<String[]> submitted;
        @Override public boolean open(Player player, String[] lines, Consumer<String[]> submitted) {
            this.lines = lines; this.submitted = submitted; return true;
        }
        @Override public void cancel(Player player) { submitted = null; }
        void submit(String[] values) { Consumer<String[]> callback = submitted; submitted = null; callback.accept(values); }
    }

    static final class Fixture implements AutoCloseable {
        final ScopeImpl scope = new ScopeImpl("chat-test");
        final List<Object[]> sent = new ArrayList<Object[]>();
        final List<Runnable> work = new ArrayList<Runnable>();
        final UUID id = UUID.randomUUID();
        final FakeSigns signs = new FakeSigns();
        final Player player;
        final Player other;
        final Plugin plugin;
        final BukkitChatPrompts prompts;
        final BukkitChatPanels panels;
        final Field serverField;
        final Object previousServer;
        boolean primary = true;
        final List<String> commands = new ArrayList<String>();
        boolean permitted = true;
        long now;

        Fixture() throws Exception { this(ChatPanelOptions.defaults()); }

        Fixture(ChatPanelOptions options) throws Exception {
            player = player(id);
            other = player(UUID.randomUUID());
            Server server = proxy(Server.class, (p, method, args) -> {
                if (method.getName().equals("isPrimaryThread")) return Boolean.valueOf(primary);
                if (method.getName().equals("getPlayer")) return args[0].equals(id) ? player : other;
                return defaultValue(method.getReturnType());
            });
            plugin = proxy(Plugin.class, (p, method, args) -> {
                if (method.getName().equals("getServer")) return server;
                if (method.getName().equals("getLogger")) return Logger.getLogger("chat-test");
                return defaultValue(method.getReturnType());
            });
            serverField = Bukkit.class.getDeclaredField("server"); serverField.setAccessible(true);
            previousServer = serverField.get(null); serverField.set(null, server);
            scope.registerCapability(SchedulerFactory.class, ignored -> new KScheduler() {
                @Override public TaskHandle every(Ticks period, Runnable task) { return new NoopTask(); }
                @Override public TaskHandle after(Ticks delay, Runnable task) {
                    NoopTask handle = new NoopTask();
                    if (delay.value() > 1) return handle; // 输入超时等长延迟在测试中不触发
                    work.add(() -> { if (!handle.cancelled) { handle.cancelled = true; task.run(); } });
                    return handle;
                }
                @Override public <T> AsyncTask<T> async(Supplier<T> supplier) { throw new UnsupportedOperationException(); }
            });
            Constructor<BukkitChatPrompts> constructor = BukkitChatPrompts.class.getDeclaredConstructor(Scope.class, Plugin.class, KLogger.class);
            constructor.setAccessible(true);
            KLogger logger = new KLogger(plugin.getLogger());
            prompts = scope.install(constructor.newInstance(scope, plugin, logger));
            panels = scope.install(new BukkitChatPanels(scope, plugin, "testpanel", prompts, options,
                    (p, message) -> sent.add(new Object[] {p, message}), signs, logger, () -> now));
        }

        void drain() {
            while (!work.isEmpty()) { List<Runnable> queued = new ArrayList<Runnable>(work); work.clear(); queued.forEach(Runnable::run); }
        }
        RichText lastMessage() {
            for (int i = sent.size() - 1; i >= 0; i--) if (sent.get(i)[0] == player) return (RichText) sent.get(i)[1];
            throw new AssertionError("nothing sent");
        }
        String last() { return lastMessage().plainText(); }
        String sentTo(Player target) {
            StringBuilder out = new StringBuilder();
            for (Object[] entry : sent) if (entry[0] == target) out.append(((RichText) entry[1]).plainText()).append('\n');
            return out.toString();
        }
        String statusLine() { return last(); }
        Stream<RichTextSegment> segments() { return lastMessage().segments().stream(); }
        String[] command(String action) {
            for (int i = sent.size() - 1; i >= 0; i--) for (RichTextSegment segment : ((RichText) sent.get(i)[1]).segments())
                if (segment.click() != null && segment.click().type() == TextAction.Type.RUN_COMMAND && segment.click().value().endsWith(" " + action))
                    return segment.click().value().split(" ");
            throw new AssertionError("missing button " + action);
        }
        void click(String action) { String[] parts = command(action); panels.dispatch(player, parts[1], parts[2]); }
        AsyncPlayerChatEvent chat(String message) { return new AsyncPlayerChatEvent(true, player, message, new HashSet<Player>()); }
        private Player player(UUID identity) {
            return proxy(Player.class, (p, method, args) -> {
                if (method.getName().equals("getUniqueId")) return identity;
                if (method.getName().equals("isOnline")) return Boolean.TRUE;
                if (method.getName().equals("hasPermission")) return Boolean.valueOf(permitted);
                if (method.getName().equals("getName")) return "tester";
                if (method.getName().equals("performCommand")) { commands.add((String) args[0]); return Boolean.TRUE; }
                return defaultValue(method.getReturnType());
            });
        }
        @Override public void close() throws ReflectiveOperationException {
            primary = true;
            try { scope.close(); } finally { serverField.set(null, previousServer); }
        }
        private static <T> T proxy(Class<T> type, InvocationHandler handler) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
        }
        private static Object defaultValue(Class<?> type) {
            if (type == Boolean.TYPE) return Boolean.FALSE;
            if (type == Integer.TYPE) return Integer.valueOf(0);
            if (type == Long.TYPE) return Long.valueOf(0);
            if (type == Double.TYPE) return Double.valueOf(0);
            if (type == Float.TYPE) return Float.valueOf(0);
            return null;
        }
        private static final class NoopTask implements TaskHandle {
            boolean cancelled;
            @Override public boolean cancel() { cancelled = true; return true; }
            @Override public boolean isCancelled() { return cancelled; }
            @Override public boolean isDone() { return cancelled; }
        }
    }
}
