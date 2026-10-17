package me.kzheart.klib.command;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import me.kzheart.klib.command.api.CommandHelpStyle;
import me.kzheart.klib.lang.RichText;
import me.kzheart.klib.lang.RichTextSegment;
import me.kzheart.klib.lang.TextAction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HelpStyleTest {
    @Test void everyPresetKeepsDescriptionsSuggestionsAndExecutablePagination() {
        for (CommandHelpStyle.Preset preset : CommandHelpStyle.Preset.values()) {
            CommandSpecImpl spec = CommandSpecImpl.command("demo");
            spec.helpStyle(CommandHelpStyle.builder(preset).pageSize(2).build());
            CommandBuiltins.create().install(spec);
            for (int i = 0; i < 4; i++) spec.route("action" + i).description("操作用途 " + i).executes(context -> {});
            List<RichText> sent = new ArrayList<RichText>();
            CommandDispatcher dispatcher = new CommandDispatcher(spec, BukkitPlayerResolver.INSTANCE, (sender, text) -> sent.add(text));
            dispatcher.execute(TestSenders.console().sender(), new String[0]);
            RichText first = sent.get(0);
            RichTextSegment next = first.segments().stream().filter(s -> s.click() != null
                    && s.click().type() == TextAction.Type.RUN_COMMAND).findFirst().orElseThrow(AssertionError::new);
            assertEquals("/demo help 2", next.click().value());
            dispatcher.execute(TestSenders.console().sender(), new String[] {"help", "2"});
            RichText second = sent.get(1);
            assertTrue(second.plainText().contains("操作用途"));
            assertTrue(second.segments().stream().anyMatch(s -> s.click() != null && s.click().type() == TextAction.Type.SUGGEST_COMMAND));
            assertEquals(2, dispatcher.renderHelp(TestSenders.console().sender(), 2, 2).page());
        }
    }

    @Test void fullSyntaxVariantsAndAncestorDescriptionsAreNotDiscarded() {
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.route("give").description("发放物品").executes(context -> {});
        spec.route("give").argument(Arguments.string("player")).argument(Arguments.integer("amount", 1, 64)).executes(context -> {});
        String help = new CommandDispatcher(spec).renderHelp(TestSenders.console().sender(), 1, 10).content().plainText();
        assertTrue(help.contains("/demo give <player> <amount>"));
        assertTrue(help.contains("发放物品"));
        CommandResult incomplete = new CommandDispatcher(spec).execute(TestSenders.console().sender(), new String[] {"give", "Alex"});
        assertTrue(incomplete.message().plainText().contains("发放物品"));
    }

    @Test void customTemplatesAndReloadableSelectionKeepLiteralDescriptionsSafe() {
        AtomicReference<CommandHelpStyle> selected = new AtomicReference<>(CommandHelpStyle.builder(CommandHelpStyle.Preset.COMPACT)
                .header("<gold>自定义 {command} {page}/{pages}")
                .entry("<gray>{index}. {usage} | {description}").build());
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.helpStyle(selected::get);
        spec.route("list").description("<click:run_command:'/op x'>原文").executes(context -> {});
        RichText help = new CommandDispatcher(spec).renderHelp(TestSenders.console().sender(), 1, 10).content();
        assertTrue(help.plainText().contains("自定义 demo"));
        assertTrue(help.plainText().contains("<click:run_command:'/op x'>原文"));
        assertFalse(help.segments().stream().anyMatch(s -> s.click() != null && s.click().value().contains("/op x")));
        selected.set(CommandHelpStyle.preset(CommandHelpStyle.Preset.PANEL));
        assertTrue(new CommandDispatcher(spec).renderHelp(TestSenders.console().sender(), 1, 10).content().plainText().contains("操作面板"));
    }
}
