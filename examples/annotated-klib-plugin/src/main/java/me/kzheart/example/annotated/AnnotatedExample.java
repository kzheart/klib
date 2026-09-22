package me.kzheart.example.annotated;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import me.kzheart.klib.KPlugin;
import me.kzheart.klib.component.*;
import me.kzheart.klib.command.*;
import me.kzheart.klib.command.annotation.*;
import me.kzheart.klib.config.ConfigModule;
import me.kzheart.klib.config.annotation.*;
import me.kzheart.klib.config.api.ConfigDocument;
import me.kzheart.klib.scheduler.*;
import me.kzheart.klib.ui.*;
import me.kzheart.klib.ui.annotation.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

/** Executable documentation for the annotation API; contains no explicit Scope plumbing. */
public final class AnnotatedExample extends KPlugin {
    private ConfigDocument<Settings> settings;
    private Menus menus;
    private ComponentHandle featureHandle;
    private Feature feature;

    @Override protected void setup() {
        ConfigModule.install(this);
        CommandModule.install(this);
        settings = configs().load(Settings.class);
        menus = Menus.install(this);
        feature = new Feature();
        featureHandle = components().install(feature);
        commands().register(new Admin());
        logger().info("ANNOTATIONS_READY library=0.5.0 party=" + settings.value().partySize);
    }

    @ConfigFile("config.yml") public static final class Settings {
        @Key("party-size") @Range(min=1, max=16) @Comment("副本队伍人数")
        public int partySize = 4;
        @Validate public void validate() {
            if (partySize < 1) throw new IllegalArgumentException("party-size must be positive");
        }
    }

    public final class Feature extends KComponent implements Listener {
        final AtomicInteger ticks = new AtomicInteger();
        final AtomicInteger asyncRuns = new AtomicInteger();
        int joins;
        boolean stopped;
        @Override protected void setup() {
            commands().register(new PlayerCommands());
            events().register(this);
            tasks().register(this);
        }
        @OnStart public void start() { logger().info("FEATURE_STARTED"); }
        @OnStop public void stop() { stopped = true; logger().info("FEATURE_STOPPED"); }
        @Every(ticks=2) public void tick() {
            if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("tick is not on main thread");
            ticks.incrementAndGet();
        }
        @Every(ticks=3, thread=TaskThread.ASYNC) public void asyncTick() {
            if (Bukkit.isPrimaryThread()) throw new IllegalStateException("async task is on main thread");
            asyncRuns.incrementAndGet();
        }
        @EventHandler public void join(PlayerJoinEvent event) {
            joins++; event.getPlayer().sendMessage("ANNOTATED_EVENT_JOIN");
        }
    }

    @Command(value="ka", aliases={"kan"})
    public final class PlayerCommands {
        @Route({"", "ui"}) public void ui(Player player) {
            menus.open(player, new Screen());
        }
        @Route("search <query>") @Check("ready")
        public void search(Player player, @Param("query") @Greedy String query) {
            player.sendMessage("SEARCH=" + query);
        }
        @Route("select <dungeon> <difficulty>")
        public void select(Player player, @Param("dungeon") @Suggest("dungeons") String dungeon,
                @Param("difficulty") @Suggest("difficulties") String difficulty) {
            player.sendMessage("SELECT=" + dungeon + "/" + difficulty);
        }
        @Suggestions("dungeons") public List<String> dungeons(SuggestionContext context) {
            return Arrays.asList("castle", "forest");
        }
        @Suggestions("difficulties") public List<String> difficulties(SuggestionContext context) {
            return Collections.singletonList(context.get("dungeon", String.class) + "-easy");
        }
        @CheckHandler("ready") public void ready() {
            if (settings.value().partySize == 1) throw new CommandRejectedException("BUSINESS_NOT_READY");
        }
        @Route("resolve <session>") public void resolve(CommandSender sender, @Param("session") UUID session) {
            sender.sendMessage("UUID=" + session);
        }
        @Route("async") public void async(CommandCall call) {
            call.await(CompletableFuture.supplyAsync(() -> "ok"),
                    result -> call.reply("ASYNC=" + result + " main=" + Bukkit.isPrimaryThread()),
                    error -> call.reply("ASYNC_FAILED"));
        }
    }

    @Command("kadmin") @Permission("klib.example.admin")
    public final class Admin {
        @Route("status") public void status(CommandSender sender) {
            sender.sendMessage("STATUS party=" + settings.value().partySize + " ticks=" + feature.ticks.get()
                    + " async=" + feature.asyncRuns.get() + " joins=" + feature.joins + " stopped=" + feature.stopped);
        }
        @Route("reload") public void reload(CommandSender sender) {
            try { settings.reload(); sender.sendMessage("CONFIG_RELOADED party=" + settings.value().partySize); }
            catch (RuntimeException failure) { sender.sendMessage("CONFIG_REJECTED party=" + settings.value().partySize); }
        }
        @Route("stop") public void stop(CommandSender sender) { featureHandle.close(); sender.sendMessage("FEATURE_CLOSED"); }
        @Route("rebuild") public void rebuildAll(CommandSender sender) {
            sender.sendMessage("REBUILD=" + AnnotatedExample.this.rebuild());
        }
    }

    @Menu(title="Annotated Dungeons", layout={"dd     nn"})
    public static final class Screen {
        private int page;
        @Entries('d') public List<MenuEntry> entries() {
            return Collections.singletonList(MenuEntry.of(new ItemStack(Material.DIAMOND, page + 1)));
        }
        @Button('n') public MenuEntry next() { return MenuEntry.of(new ItemStack(Material.ARROW)); }
        @Click('n') public void next(MenuClick click) {
            page = (page + 1) % 16;
            click.refresh();
            click.player().sendMessage("MENU_PAGE=" + page);
        }
    }
}
