package me.kzheart.klib.script;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 游戏类语句经 ScriptPlatform / PlayerQuery / CommandSink 传给宿主的参数。 */
class GameScriptActionsTest {
    private final KetherScriptEngine engine = new KetherScriptEngine(new StatementRegistry(), null, Runnable::run, true);
    private final List<String> calls = new ArrayList<String>();
    private final Map<String, Object> properties = new HashMap<String, Object>();

    private ScriptContext context(Object sender) {
        return ScriptContext.builder().sender(sender)
                .service(ScriptSenderQuery.class, new ScriptSenderQuery() {
                    @Override public boolean isPlayer(Object target) { return String.valueOf(target).startsWith("player:"); }
                    @Override public String name(Object target) { return String.valueOf(target).replace("player:", ""); }
                })
                .service(MessageSink.class, (target, message) -> calls.add("tell " + target + " " + message))
                .service(CommandSink.class, new CommandSink() {
                    @Override public Object dispatch(Object target, String command) { calls.add("command " + target + " " + command); return true; }
                    @Override public Object dispatchAsOperator(Object target, String command) { calls.add("op " + target + " " + command); return true; }
                })
                .service(PlayerQuery.class, new PlayerQuery() {
                    @Override public Object property(Object target, String name) { return properties.get(name); }
                    @Override public boolean hasPermission(Object target, String permission) { return true; }
                    @Override public boolean write(Object target, String name, Method method, Object value) {
                        calls.add("write " + name + " " + method + " " + value);
                        return !"locked".equals(name);
                    }
                })
                .service(ScriptPlatform.class, new ScriptPlatform() {
                    @Override public List<String> onlinePlayerNames() { return Arrays.asList("Alex", "Bob"); }
                    @Override public void broadcast(String message) { calls.add("broadcast " + message); }
                    @Override public Object console() { return "console"; }
                    @Override public Object player(String name) { return "player:" + name; }
                    @Override public void actionBar(Object player, String message) { calls.add("actionbar " + player + " " + message); }
                    @Override public void title(Object player, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
                        calls.add("title " + title + "|" + subtitle + "|" + fadeIn + "," + stay + "," + fadeOut);
                    }
                    @Override public void playSound(Object player, String sound, float volume, float pitch) { calls.add("sound " + sound + " " + volume + " " + pitch); }
                    @Override public void stopSound(Object player, String sound) { calls.add("stopsound " + sound); }
                    @Override public Object location(String world, double x, double y, double z, float yaw, float pitch) {
                        return world + "@" + x + "," + y + "," + z + "," + yaw + "," + pitch;
                    }
                    @Override public Object material(String name) { return "material:" + name; }
                    @Override public Object itemStack(String material) { return "item:" + material; }
                    @Override public void scoreboard(Object player, List<String> lines) { calls.add("scoreboard " + lines); }
                })
                .service(JavaScriptEvaluator.class, (source, bindings) -> source + ":" + bindings.get("sender") + ":" + bindings.get("n"))
                .build();
    }

    private Object eval(String source) { return engine.eval(source, context("player:Alex")).toCompletableFuture().join(); }

    @Test void messagesTitlesAndSounds() {
        eval("actionbar \"hi @sender\"\nbc \"all @sender\"\ntitle a subtitle \"b @sender\" by 1 2 3\nsubtitle c\nsound block.note_block.pling by 1 2\nsound resource:custom.x\nstopsound ui.button.click");
        assertEquals(Arrays.asList("actionbar player:Alex hi Alex", "broadcast all Alex", "title a|b Alex|1,2,3", "title §r|c|0,20,0",
                "sound BLOCK_NOTE_BLOCK_PLING 1.0 2.0", "sound resource:custom.x 0.0 0.0", "stopsound UI_BUTTON_CLICK"), calls);
    }

    @Test void playersLocationAndItems() {
        assertEquals(Arrays.asList("Alex", "Bob"), eval("players"));
        assertEquals("world@1.0,2.0,3.0,0.0,0.0", eval("loc world 1 2 3"));
        assertEquals("world@1.0,2.0,3.0,90.0,10.0", eval("location world 1 2 3 and 90 10"));
        assertEquals("item:diamond", eval("itemstack diamond"));
        assertEquals("material:stone", eval("material stone"));
    }

    @Test void playerOperatorsMatchMultiWordNamesAndWrite() {
        properties.put("block x", 12);
        properties.put("bed spawn x", 5);
        properties.put("level", 3);
        assertEquals(12, eval("player block x"));
        assertEquals(5, eval("player bed spawn x"));
        assertEquals(Boolean.TRUE, eval("check player level == 3"));
        eval("player level add 2\nplayer health to 10\nplayer exp - 1");
        assertEquals(Arrays.asList("write level INCREASE 2", "write health MODIFY 10", "write exp DECREASE 1"), calls);
        assertThrows(RuntimeException.class, () -> eval("player locked to 1"));
    }

    @Test void commandModesAndSenderPlaceholder() {
        eval("command \"say @sender\"\ncommand \"give @sender stone\" as op\ncommand \"say hi @sender\" by console");
        assertEquals(Arrays.asList("command player:Alex say Alex", "op player:Alex give Alex stone", "command null say hi console"), calls);
    }

    @Test void switchChangesTheSenderForLaterStatements() {
        eval("switch Bob\ntell hello\nswitch console\ntell world");
        assertEquals(Arrays.asList("tell player:Bob hello", "tell console world"), calls);
    }

    @Test void scoreboardSplitsTextIntoLines() {
        ScriptContext context = context("player:Alex");
        context.setVariable("board", "Title\nline");
        engine.eval("scoreboard &board\nscoreboard array [ T a b ]\nscoreboard null", context).toCompletableFuture().join();
        assertEquals(Arrays.asList("scoreboard [Title, line]", "scoreboard [T, a, b]", "scoreboard null"), calls);
    }

    @Test void javascriptReceivesSenderAndVariables() {
        assertEquals("1+1:player:Alex:7", engine.eval("set n to 7\njs \"1+1\"", context("player:Alex")).toCompletableFuture().join());
    }

    @Test void gameStatementsRequireAPlayer() {
        assertThrows(RuntimeException.class, () -> engine.eval("actionbar x", context("console")).toCompletableFuture().join());
    }
}
