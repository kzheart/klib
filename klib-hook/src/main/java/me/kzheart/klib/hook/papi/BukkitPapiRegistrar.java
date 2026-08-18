package me.kzheart.klib.hook.papi;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.kzheart.klib.hook.Texts;
import me.kzheart.klib.scope.Disposable;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

/** 将 klib 占位符解析器注册为真正的 PlaceholderAPI 扩展。 */
public final class BukkitPapiRegistrar implements PapiRegistrar {

    private final String author;
    private final String version;
    private final boolean persist;
    private final ExpansionLifecycle lifecycle;

    public BukkitPapiRegistrar(Plugin plugin) {
        this(plugin, false);
    }

    public BukkitPapiRegistrar(Plugin plugin, boolean persist) {
        this(
                Objects.requireNonNull(plugin, "plugin").getName(),
                plugin.getDescription().getVersion(),
                persist,
                new DirectExpansionLifecycle());
    }

    public BukkitPapiRegistrar(String author, String version) {
        this(author, version, false);
    }

    /** {@code persist} 控制扩展能否在 PlaceholderAPI 重载后保留；默认为 false。 */
    public BukkitPapiRegistrar(String author, String version, boolean persist) {
        this(author, version, persist, new DirectExpansionLifecycle());
    }

    BukkitPapiRegistrar(String author, String version, ExpansionLifecycle lifecycle) {
        this(author, version, false, lifecycle);
    }

    BukkitPapiRegistrar(String author, String version, boolean persist, ExpansionLifecycle lifecycle) {
        this.author = Texts.requireText(author, "author");
        this.version = Texts.requireText(version, "version");
        this.persist = persist;
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    @Override
    public Disposable register(String identifier, PapiExpansion expansion) {
        ManagedExpansion managed = new ManagedExpansion(
                Texts.requireText(identifier, "identifier"),
                author,
                version,
                persist,
                Objects.requireNonNull(expansion, "expansion"));
        if (!lifecycle.register(managed)) {
            throw new IllegalStateException(
                    "PlaceholderAPI rejected expansion: " + managed.getIdentifier());
        }
        AtomicBoolean disposed = new AtomicBoolean();
        return () -> {
            if (disposed.compareAndSet(false, true)) {
                lifecycle.unregister(managed);
            }
        };
    }

    interface ExpansionLifecycle {
        boolean register(Object expansion);

        void unregister(Object expansion);
    }

    private static final class DirectExpansionLifecycle implements ExpansionLifecycle {
        @Override
        public boolean register(Object expansion) {
            return ((PlaceholderExpansion) expansion).register();
        }

        @Override
        public void unregister(Object expansion) {
            ((PlaceholderExpansion) expansion).unregister();
        }
    }

    private static final class ManagedExpansion extends PlaceholderExpansion {
        private final String identifier;
        private final String author;
        private final String version;
        private final boolean persist;
        private final PapiExpansion expansion;

        private ManagedExpansion(
                String identifier,
                String author,
                String version,
                boolean persist,
                PapiExpansion expansion
        ) {
            this.identifier = identifier;
            this.author = author;
            this.version = version;
            this.persist = persist;
            this.expansion = expansion;
        }

        @Override
        public String getIdentifier() {
            return identifier;
        }

        @Override
        public String getAuthor() {
            return author;
        }

        @Override
        public String getVersion() {
            return version;
        }

        @Override
        public boolean persist() {
            return persist;
        }

        @Override
        public String onRequest(OfflinePlayer player, String parameters) {
            return expansion.resolve(player, parameters);
        }
    }
}
