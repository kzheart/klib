package me.kzheart.klib.script.kether.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class KetherCoreGoldenTest {

    @Test
    void unresolvedDeferredVariableFailsFastInsteadOfBlockingCaller() {
        CompletableFuture<String> pending = new CompletableFuture<String>();
        AbstractQuestContext.SimpleVarTable variables =
                new AbstractQuestContext.SimpleVarTable(null);
        variables.set("pending", ParsedAction.noop(), pending);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> variables.get("pending"));

        assertTrue(failure.getMessage().contains("getFuture"));
    }

    @Test
    void composesAndExecutesApplicativeParser() throws Exception {
        try (SimpleQuestService service = new SimpleQuestService()) {
            Parser<Integer> first = Parser.of(QuestReader::nextInt);
            Parser<Integer> sum = first.fold(Parser.of(QuestReader::nextInt), Integer::sum);
            service.getRegistry().registerAction("sum", Parser.build(sum.map(Parser.Action::point)));

            Quest quest = service.load("parser-golden", "def main = { sum 20 22 }");
            Object result = service.newContext(quest).runActions().get(2, TimeUnit.SECONDS);

            assertEquals(42, result);
        }
    }

    @Test
    void loadsBuildsAndExecutesQuestWithAnonymousAndAsyncActions() throws Exception {
        try (SimpleQuestService service = new SimpleQuestService()) {
            installExecutionGoldenActions(service);
            String source = "def main = {\n"
                    + "  set score *40\n"
                    + "  set answer { add &score *2 }\n"
                    + "  defer &answer\n"
                    + "}";

            Quest quest = service.load("execution-golden", source, Collections.singletonList("kether"));
            assertEquals(2, quest.getBlocks().size());
            assertEquals(3, quest.getBlock("main").orElseThrow(AssertionError::new).getActions().size());

            SimpleQuestContext context = service.newContext(quest);
            Object result = context.runActions().get(2, TimeUnit.SECONDS);

            assertEquals(42, result);
            assertEquals("40", context.rootFrame().variables().get("score").orElse(null));
            assertEquals(42, context.rootFrame().variables().get("answer").orElse(null));
            assertEquals(ExitStatus.success(), context.getExitStatus().orElse(null));
            ParsedAction<?> first = quest.getBlock("main").orElseThrow(AssertionError::new).get(0)
                    .orElseThrow(AssertionError::new);
            assertTrue(quest.blockOf(first).isPresent());
        }
    }

    @Test
    void tokenizesHighFrequencyAndLongTailCorpus() throws IOException {
        assertGolden("high-frequency/01-simple-stall-condition.kether",
                word("check"), word("papi"), word("%wealth_level%"), word(">="), word("5"));
        assertGolden("high-frequency/02-fishx-reward.kether",
                word("tell"), word("你触发了普通钓鱼奖励"), word("tell"), block("这里是多行 Kether 示例"));
        assertGolden("high-frequency/03-entrance-command.kether",
                word("command"), word("inline"), block("say 激活魔狼入口-随机"), word("as"), word("console"));
        assertGolden("high-frequency/04-entrance-condition.kether",
                word("all"), word("["), word("check"), word("player"), word("level"), word(">"),
                word("1"), word("perm"), block("luckprems.vip"), word("]"));
        assertGolden("high-frequency/05-read-book-condition.kether",
                word("type"), word("boolean"), block("true"));
        assertGolden("high-frequency/06-player-reset-reward.kether",
                word("tell"), word("日常奖励"));
        assertGolden("long-tail/01-reference-and-literal.kether",
                word("set"), word("selected"), word("to"), word("&target"), word("tell"), word("*selected"));
        assertGolden("long-tail/02-single-quoted.kether",
                word("tell"), block("包含 空格 的单引号文本"));
        assertGolden("long-tail/03-triple-quoted.kether",
                word("tell"), block("第一行\n第二行"));
        assertGolden("long-tail/04-named-block.kether",
                word("def"), word("main"), word("="),
                action("\n  tell \"开始\"\n  command inline \"say 完成\" as console\n"));
    }

    @Test
    void preservesMarkResetAndRejectsUnclosedStrings() {
        KetherTokenReader reader = new KetherTokenReader("tell \"ok\"");
        reader.mark();
        assertEquals("tell", reader.nextToken());
        reader.reset();
        assertEquals("tell", reader.nextToken());
        assertTrue(reader.nextTokenBlock().isBlock());

        KetherTokenReader doubleQuoted = new KetherTokenReader("tell \"open");
        assertEquals("tell", doubleQuoted.nextToken());
        KetherLexException doubleError = assertThrows(KetherLexException.class, doubleQuoted::nextTokenBlock);
        assertEquals(5, doubleError.getPosition());

        KetherTokenReader singleQuoted = new KetherTokenReader("tell 'open");
        assertEquals("tell", singleQuoted.nextToken());
        KetherLexException singleError = assertThrows(KetherLexException.class, singleQuoted::nextTokenBlock);
        assertEquals(5, singleError.getPosition());
    }

    private static void assertGolden(String resource, ExpectedToken... expected) throws IOException {
        KetherTokenReader reader = new KetherTokenReader(read(resource));
        List<ExpectedToken> actual = new ArrayList<>();
        while (reader.hasNext()) {
            TokenBlock token = reader.nextTokenBlock();
            actual.add(new ExpectedToken(token.getToken(), token.isBlock(), token.isActionBlock()));
        }
        assertEquals(Arrays.asList(expected), actual, resource);
    }

    private static String read(String resource) throws IOException {
        String path = "/kether-corpus/" + resource;
        try (InputStream input = KetherCoreGoldenTest.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Missing golden resource " + path);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static ExpectedToken word(String value) {
        return new ExpectedToken(value, false);
    }

    private static ExpectedToken block(String value) {
        return new ExpectedToken(value, true, false);
    }

    private static void installExecutionGoldenActions(SimpleQuestService service) {
        service.getRegistry().registerAction("set", QuestActionParser.of(reader -> {
            String name = reader.nextToken();
            ParsedAction<?> value = reader.nextParsedAction();
            return new QuestAction<Object>() {
                @Override
                public CompletableFuture<Object> process(QuestContext.Frame frame) {
                    return frame.newFrame(value).run().thenApply(result -> {
                        frame.variables().set(name, result);
                        return result;
                    });
                }
            };
        }));
        service.getRegistry().registerAction("add", QuestActionParser.of(reader -> {
            ParsedAction<?> left = reader.nextParsedAction();
            ParsedAction<?> right = reader.nextParsedAction();
            return new QuestAction<Integer>() {
                @Override
                public CompletableFuture<Integer> process(QuestContext.Frame frame) {
                    CompletableFuture<?> leftFuture = frame.newFrame(left).run();
                    CompletableFuture<?> rightFuture = frame.newFrame(right).run();
                    return leftFuture.thenCombine(rightFuture,
                            (first, second) -> Integer.parseInt(String.valueOf(first))
                                    + Integer.parseInt(String.valueOf(second)));
                }
            };
        }));
        service.getRegistry().registerAction("defer", QuestActionParser.of(reader -> {
            ParsedAction<?> value = reader.nextParsedAction();
            return new QuestAction<Object>() {
                @Override
                public CompletableFuture<Object> process(QuestContext.Frame frame) {
                    return frame.newFrame(value).run().thenCompose(result -> {
                        CompletableFuture<Object> delayed = new CompletableFuture<>();
                        service.getAsyncExecutor().schedule(() -> delayed.complete(result), 5, TimeUnit.MILLISECONDS);
                        return delayed;
                    });
                }
            };
        }));
    }

    private static ExpectedToken action(String value) {
        return new ExpectedToken(value, true, true);
    }

    private static final class ExpectedToken {

        private final String value;
        private final boolean block;
        private final boolean action;

        private ExpectedToken(String value, boolean block) {
            this(value, block, false);
        }

        private ExpectedToken(String value, boolean block, boolean action) {
            this.value = value;
            this.block = block;
            this.action = action;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ExpectedToken)) {
                return false;
            }
            ExpectedToken that = (ExpectedToken) other;
            return block == that.block && action == that.action && value.equals(that.value);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * value.hashCode() + (block ? 1 : 0)) + (action ? 1 : 0);
        }

        @Override
        public String toString() {
            return (action ? "action:" : block ? "block:" : "word:") + value;
        }
    }
}
