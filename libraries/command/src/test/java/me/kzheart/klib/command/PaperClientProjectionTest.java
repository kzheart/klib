package me.kzheart.klib.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import me.kzheart.klib.scope.Disposable;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class PaperClientProjectionTest {
    @Test void filtersEveryPermissionNodeAndReusesServerSuggestionsWithoutMutatingOriginal() throws Exception {
        PaperLifecycleCommandBridgeTest.Fixture fixture = new PaperLifecycleCommandBridgeTest.Fixture();
        BrigadierBridge.PaperRegistry registry = new BrigadierBridge.PaperRegistry(fixture.plugin, fixture.map);
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.literal("public", node -> node.argument(Arguments.string("item"), arg -> arg.executes(ctx -> { })));
        spec.literal("admin", node -> node.permission("test.admin").literal("secret", arg -> arg.executes(ctx -> { })));
        spec.literal("nested", node -> node.literal("hidden", child -> child.permission("test.secret").executes(ctx -> { })));
        registry.register("demo", BrigadierTree.from(spec));
        own(fixture, "demo");
        SuggestionProvider<Object> suggestions = (context, builder) -> builder.suggest("物品:一").buildFuture();
        LiteralCommandNode<Object> raw = raw("demo", suggestions);
        ClientRoot copy = new ClientRoot(); copy.addChild(raw);
        Player player = (Player) TestSenders.player("viewer").sender();
        registry.project(player, copy);
        CommandNode<Object> projected = copy.getChild("demo");
        assertNotSame(raw, projected);
        assertNotNull(raw.getChild("args")); assertNull(raw.getChild("public"));
        assertNotNull(projected.getChild("public")); assertNull(projected.getChild("admin"));
        assertNull(projected.getChild("nested").getChild("hidden"));
        ArgumentCommandNode<?, ?> item = (ArgumentCommandNode<?, ?>) projected.getChild("public").getChild("item");
        assertSame(suggestions, item.getCustomSuggestions()); assertNotNull(item.getCommand());
        assertEquals(Collections.singleton("物品:一"), Collections.singleton(
                item.getCustomSuggestions().getSuggestions(null, new SuggestionsBuilder("", 0))
                        .join().getList().get(0).getText()));
    }

    @Test void allowedPlayerSeesProtectedTreeAndUnicodeNamespacedAlias() {
        PaperLifecycleCommandBridgeTest.Fixture fixture = new PaperLifecycleCommandBridgeTest.Fixture();
        BrigadierBridge.PaperRegistry registry = new BrigadierBridge.PaperRegistry(fixture.plugin, fixture.map);
        CommandSpecImpl spec = CommandSpecImpl.command("邮箱");
        spec.literal("领取", node -> node.permission("test.admin").argument(Arguments.string("物品"), arg -> arg.executes(ctx -> { })));
        registry.register("邮箱", BrigadierTree.from(spec)); own(fixture, "test:邮箱");
        ClientRoot copy = new ClientRoot(); copy.addChild(raw("test:邮箱", null));
        registry.project((Player) TestSenders.player("admin", "test.admin").sender(), copy);
        assertNotNull(copy.getChild("test:邮箱").getChild("领取").getChild("物品"));
    }

    @Test void cannotReplaceACommandNowOwnedByAnotherPlugin() {
        PaperLifecycleCommandBridgeTest.Fixture fixture = new PaperLifecycleCommandBridgeTest.Fixture();
        PaperLifecycleCommandBridgeTest.Fixture other = new PaperLifecycleCommandBridgeTest.Fixture();
        BrigadierBridge.PaperRegistry registry = new BrigadierBridge.PaperRegistry(fixture.plugin, fixture.map);
        registry.register("demo", BrigadierTree.from(CommandSpecImpl.command("demo")));
        fixture.known.put("demo", new PaperLifecycleCommandBridgeTest.OwnedCommand("demo", other.plugin));
        ClientRoot copy = new ClientRoot(); LiteralCommandNode<Object> raw = raw("demo", null); copy.addChild(raw);
        registry.project((Player) TestSenders.player("viewer").sender(), copy);
        assertSame(raw, copy.getChild("demo"));
    }

    @Test void closedOrRootDeniedBindingsAreRemovedOnlyFromPlayerCopy() {
        PaperLifecycleCommandBridgeTest.Fixture fixture = new PaperLifecycleCommandBridgeTest.Fixture();
        BrigadierBridge.PaperRegistry registry = new BrigadierBridge.PaperRegistry(fixture.plugin, fixture.map);
        CommandSpecImpl denied = CommandSpecImpl.command("denied"); denied.permission("test.admin");
        registry.register("denied", BrigadierTree.from(denied));
        Disposable handle = registry.register("closed", BrigadierTree.from(CommandSpecImpl.command("closed"))); handle.dispose();
        own(fixture, "denied"); own(fixture, "closed");
        ClientRoot copy = new ClientRoot(); copy.addChild(raw("denied", null)); copy.addChild(raw("closed", null));
        registry.project((Player) TestSenders.player("viewer").sender(), copy);
        assertNull(copy.getChild("denied")); assertNull(copy.getChild("closed"));
        assertEquals(2, fixture.known.size());
    }

    @Test void asynchronousEventDoesNotReadOrChangePlayerState() {
        PaperLifecycleCommandBridgeTest.Fixture fixture = new PaperLifecycleCommandBridgeTest.Fixture();
        BrigadierBridge.PaperRegistry registry = new BrigadierBridge.PaperRegistry(fixture.plugin, fixture.map);
        registry.register("demo", BrigadierTree.from(CommandSpecImpl.command("demo"))); own(fixture, "demo");
        ClientRoot copy = new ClientRoot(); LiteralCommandNode<Object> raw = raw("demo", null); copy.addChild(raw);
        registry.execute(null, new SendEvent(true, copy));
        assertSame(raw, copy.getChild("demo"));
        registry.execute(null, new SendEvent(false, copy));
        assertNotSame(raw, copy.getChild("demo"));
    }

    private static void own(PaperLifecycleCommandBridgeTest.Fixture fixture, String label) {
        fixture.known.put(label, new PaperLifecycleCommandBridgeTest.OwnedCommand(label, fixture.plugin));
    }
    private static LiteralCommandNode<Object> raw(String name, SuggestionProvider<Object> suggestions) {
        RequiredArgumentBuilder<Object, String> args = RequiredArgumentBuilder.argument("args", StringArgumentType.greedyString());
        if (suggestions != null) args.suggests(suggestions);
        return LiteralArgumentBuilder.literal(name).executes(ctx -> 1).then(args).build();
    }
    /** Paper 公开的 removeCommand 补丁只在测试根上模拟；其余节点使用真实 Brigadier。 */
    public static final class ClientRoot extends RootCommandNode<Object> {
        public void removeCommand(String name) { getChildren().removeIf(node -> node.getName().equals(name)); }
    }
    public static final class SendEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        private final ClientRoot root;
        SendEvent(boolean async, ClientRoot root) { super(async); this.root = root; }
        public Player getPlayer() {
            if (isAsynchronous()) throw new AssertionError("Async player access");
            return (Player) TestSenders.player("viewer").sender();
        }
        public ClientRoot getCommandNode() { return root; }
        @Override public HandlerList getHandlers() { return HANDLERS; }
    }
}
