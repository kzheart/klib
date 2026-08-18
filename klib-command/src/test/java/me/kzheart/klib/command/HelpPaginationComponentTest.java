package me.kzheart.klib.command;

import me.kzheart.klib.lang.RichTextSegment;
import me.kzheart.klib.lang.MessageColor;
import me.kzheart.klib.lang.TextAction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelpPaginationComponentTest {
    @Test
    void buildsPagedHoverAndClickComponents() {
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.literal("one", child -> child.description("第一个命令").executes(context -> {
        }));
        spec.literal("two", child -> child.description("第二个命令").executes(context -> {
        }));
        spec.literal("three", child -> child.description("第三个命令").executes(context -> {
        }));
        CommandDispatcher dispatcher = new CommandDispatcher(spec);

        HelpPage page = dispatcher.renderHelp(TestSenders.console().sender(), 1, 2);

        assertEquals(1, page.page());
        assertEquals(2, page.totalPages());
        RichTextSegment command = page.content().segments().stream()
                .filter(segment -> segment.text().equals("/demo"))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertNotNull(command.hover());
        assertEquals(TextAction.Type.HOVER_TEXT, command.hover().type());
        assertNotNull(command.click());
        assertEquals(TextAction.Type.SUGGEST_COMMAND, command.click().type());
        RichTextSegment next = page.content().segments().stream()
                .filter(segment -> segment.text().contains("下一页"))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertEquals(TextAction.Type.RUN_COMMAND, next.click().type());
        assertEquals("/demo help 2", next.click().value());
    }

    @Test
    void inlinesDescriptionAndMergesLiteralPathVariants() {
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        CommandBuiltins.create().help(true, null).install(spec);
        spec.literal("give", child -> child.description("发放物品").executes(context -> {
        }));
        CommandDispatcher dispatcher = new CommandDispatcher(spec);

        HelpPage page = dispatcher.renderHelp(TestSenders.console().sender(), 1, 10);
        String plain = page.content().plainText();

        // help 与 help <page> 合并为一条
        assertEquals(1, countOccurrences(plain, "/demo help"));
        // 描述内联在条目行内
        assertTrue(plain.contains("/demo give - 发放物品"));
        // 点击建议在 `[`/`<` 处截断，占位符不进输入框
        RichTextSegment help = page.content().segments().stream()
                .filter(segment -> segment.text().equals("help"))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertEquals("/demo help ", help.click().value());
    }

    @Test
    void stylesCommandLiteralsAndArgumentsAsDistinctLevels() {
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.literal("give", child -> child.argument(
                Arguments.string("player"),
                argument -> argument.executes(context -> { })));
        HelpPage page = new CommandDispatcher(spec)
                .renderHelp(TestSenders.console().sender(), 1, 10);

        assertEquals(MessageColor.GOLD, segment(page, "/demo").color());
        assertEquals(MessageColor.YELLOW, segment(page, "give").color());
        assertEquals(MessageColor.GRAY, segment(page, "<player>").color());
    }

    private static RichTextSegment segment(HelpPage page, String text) {
        return page.content().segments().stream()
                .filter(value -> value.text().equals(text))
                .findFirst()
                .orElseThrow(AssertionError::new);
    }

    private static int countOccurrences(String source, String target) {
        int count = 0;
        int index = source.indexOf(target);
        while (index >= 0) {
            count++;
            index = source.indexOf(target, index + 1);
        }
        return count;
    }

    @Test
    void plainSinkDegradesComponentsForConsole() {
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.literal("one", child -> child.description("说明").executes(context -> {
        }));
        TestSenders.SenderFixture console = TestSenders.console();
        CommandDispatcher dispatcher = new CommandDispatcher(spec);

        HelpPage page = dispatcher.sendHelp(console.sender(), 1, 8);

        assertEquals(1, console.messages().size());
        assertEquals(page.content().legacyText(), console.messages().get(0));
        assertTrue(console.messages().get(0).contains("/demo"));
        assertTrue(console.messages().get(0).contains("one"));
    }
}
