package me.kzheart.klib.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class KetherCorpusExecutionGoldenTest {

    @Test
    void executesSimpleStallCondition() throws IOException {
        ScriptContext context = base().service(
                PlaceholderResolver.class,
                (sender, text) -> text.replace("%wealth_level%", "7")).build();

        assertEquals(Boolean.TRUE, execute("high-frequency/01-simple-stall-condition.kether", context));
    }

    @Test
    void executesFishRewardMessages() throws IOException {
        List<String> messages = new ArrayList<String>();
        ScriptContext context = base().service(MessageSink.class, (sender, text) -> messages.add(text)).build();

        assertEquals("这里是多行 Kether 示例", execute("high-frequency/02-fishx-reward.kether", context));
        assertEquals(java.util.Arrays.asList("你触发了普通钓鱼奖励", "这里是多行 Kether 示例"), messages);
    }

    @Test
    void executesEntranceConsoleCommand() throws IOException {
        AtomicReference<Object> senderSeen = new AtomicReference<Object>(new Object());
        AtomicReference<String> commandSeen = new AtomicReference<String>();
        ScriptContext context = base().sender("player").service(CommandSink.class, (sender, command) -> {
            senderSeen.set(sender);
            commandSeen.set(command);
            return "command-ok";
        }).build();

        assertEquals("command-ok", execute("high-frequency/03-entrance-command.kether", context));
        assertNull(senderSeen.get());
        assertEquals("say 激活魔狼入口-随机", commandSeen.get());
    }

    @Test
    void executesEntranceAllCondition() throws IOException {
        ScriptContext context = base().sender("player").service(PlayerQuery.class, new PlayerQuery() {
            @Override
            public Object property(Object sender, String name) {
                assertEquals("level", name);
                return Integer.valueOf(2);
            }

            @Override
            public boolean hasPermission(Object sender, String permission) {
                return "luckprems.vip".equals(permission);
            }
        }).build();

        assertEquals(Boolean.TRUE, execute("high-frequency/04-entrance-condition.kether", context));
    }

    @Test
    void executesReadBookTypeConversion() throws IOException {
        assertEquals(Boolean.TRUE, execute(
                "high-frequency/05-read-book-condition.kether",
                base().build()));
    }

    @Test
    void executesPlayerResetRewardMessage() throws IOException {
        List<String> messages = new ArrayList<String>();
        ScriptContext context = base().service(MessageSink.class, (sender, text) -> messages.add(text)).build();

        assertEquals("日常奖励", execute("high-frequency/06-player-reset-reward.kether", context));
        assertEquals(java.util.Collections.singletonList("日常奖励"), messages);
    }

    @Test
    void executesReferenceAndLiteralPrefixes() throws IOException {
        List<String> messages = new ArrayList<String>();
        ScriptContext context = base()
                .variable("target", "resolved-target")
                .service(MessageSink.class, (sender, text) -> messages.add(text))
                .build();

        assertEquals("selected", execute("long-tail/01-reference-and-literal.kether", context));
        assertEquals("resolved-target", context.variableOrNull("selected"));
        assertEquals(java.util.Collections.singletonList("selected"), messages);
    }

    @Test
    void executesSingleQuotedLiteral() throws IOException {
        List<String> messages = new ArrayList<String>();
        ScriptContext context = base().service(MessageSink.class, (sender, text) -> messages.add(text)).build();

        assertEquals("包含 空格 的单引号文本", execute("long-tail/02-single-quoted.kether", context));
        assertEquals(java.util.Collections.singletonList("包含 空格 的单引号文本"), messages);
    }

    @Test
    void executesTripleQuotedLiteral() throws IOException {
        List<String> messages = new ArrayList<String>();
        ScriptContext context = base().service(MessageSink.class, (sender, text) -> messages.add(text)).build();

        assertEquals("第一行\n第二行", execute("long-tail/03-triple-quoted.kether", context));
        assertEquals(java.util.Collections.singletonList("第一行\n第二行"), messages);
    }

    @Test
    void executesNamedMainBlockAndItsSideEffects() throws IOException {
        List<String> messages = new ArrayList<String>();
        AtomicReference<Object> senderSeen = new AtomicReference<Object>(new Object());
        AtomicReference<String> commandSeen = new AtomicReference<String>();
        ScriptContext context = base()
                .sender("player")
                .service(MessageSink.class, (sender, text) -> messages.add(text))
                .service(CommandSink.class, (sender, command) -> {
                    senderSeen.set(sender);
                    commandSeen.set(command);
                    return Boolean.TRUE;
                })
                .build();

        assertEquals(Boolean.TRUE, execute("long-tail/04-named-block.kether", context));
        assertEquals(java.util.Collections.singletonList("开始"), messages);
        assertNull(senderSeen.get());
        assertEquals("say 完成", commandSeen.get());
    }

    private static ScriptContext.Builder base() {
        return ScriptContext.builder();
    }

    private static Object execute(String resource, ScriptContext context) throws IOException {
        String source = read(resource);
        Object result = new KetherScriptEngine(new StatementRegistry())
                .eval(source, context)
                .toCompletableFuture()
                .join();
        assertTrue(source.length() > 0);
        return result;
    }

    private static String read(String resource) throws IOException {
        String path = "/kether-corpus/" + resource;
        try (InputStream input = KetherCorpusExecutionGoldenTest.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Missing corpus resource " + path);
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
}
