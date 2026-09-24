package me.kzheart.klib.item;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 用统一的 {@code prefix:id} 引用生成和识别外部物品。
 *
 * <p>没有前缀或前缀为 {@code minecraft} 的引用视为原版材质，例如 {@code DIAMOND}。
 * 原版引用只匹配不被任何已注册外部系统识别的物品，因此 MMOItems 的钻石不会被当作普通钻石扣除。
 *
 * <p>实例不可变且线程安全，但生成和识别物品时会调用外部插件，调用方仍应遵守这些插件的线程要求。
 */
public final class ExternalItems implements ExternalItemProvider {
    private static final Map<String, String> BUILTIN_PLUGINS;
    private static final Map<String, Function<ClassLoader, ExternalItemSource>> BUILTIN_FACTORIES;

    static {
        Map<String, String> plugins = new LinkedHashMap<String, String>();
        Map<String, Function<ClassLoader, ExternalItemSource>> factories =
                new LinkedHashMap<String, Function<ClassLoader, ExternalItemSource>>();
        plugins.put("mi", "MMOItems");
        factories.put("mi", ExternalItemSources::mmoItems);
        plugins.put("ni", "NeigeItems");
        factories.put("ni", ExternalItemSources::neigeItems);
        plugins.put("ia", "ItemsAdder");
        factories.put("ia", ExternalItemSources::itemsAdder);
        plugins.put("mm", "MythicMobs");
        factories.put("mm", ExternalItemSources::mythicMobs);
        BUILTIN_PLUGINS = Collections.unmodifiableMap(plugins);
        BUILTIN_FACTORIES = Collections.unmodifiableMap(factories);
    }

    private final Map<String, ExternalItemSource> sources;
    private final Map<String, SourceState> states;

    private ExternalItems(Map<String, ExternalItemSource> sources, Map<String, SourceState> states) {
        this.sources = Collections.unmodifiableMap(new LinkedHashMap<String, ExternalItemSource>(sources));
        this.states = Collections.unmodifiableMap(new LinkedHashMap<String, SourceState>(states));
    }

    /** 探测已启用的 MMOItems、NeigeItems、ItemsAdder 与 MythicMobs。应在这些插件启用之后调用。 */
    public static ExternalItems detect() {
        return detect(Bukkit.getPluginManager());
    }

    public static ExternalItems detect(PluginManager plugins) {
        Objects.requireNonNull(plugins, "plugins");
        Map<String, ExternalItemSource> sources = new LinkedHashMap<String, ExternalItemSource>();
        Map<String, SourceState> states = new LinkedHashMap<String, SourceState>();
        for (Map.Entry<String, String> entry : BUILTIN_PLUGINS.entrySet()) {
            String prefix = entry.getKey();
            String pluginName = entry.getValue();
            Plugin plugin = plugins.getPlugin(pluginName);
            if (plugin == null || !plugin.isEnabled()) {
                states.put(prefix, new SourceState(prefix, pluginName, Status.MISSING, "未安装或未启用"));
                continue;
            }
            try {
                sources.put(prefix, BUILTIN_FACTORIES.get(prefix).apply(plugin.getClass().getClassLoader()));
                states.put(prefix, new SourceState(prefix, pluginName, Status.AVAILABLE, "已挂钩"));
            } catch (RuntimeException | LinkageError failure) {
                states.put(prefix, new SourceState(prefix, pluginName, Status.FAILED, describe(failure)));
            }
        }
        return new ExternalItems(sources, states);
    }

    public static ExternalItems of(ExternalItemSource... sources) {
        return empty().with(sources);
    }

    public static ExternalItems empty() {
        return new ExternalItems(Collections.<String, ExternalItemSource>emptyMap(),
                Collections.<String, SourceState>emptyMap());
    }

    /** 返回追加或替换了指定适配器的新实例。 */
    public ExternalItems with(ExternalItemSource... additions) {
        Map<String, ExternalItemSource> nextSources = new LinkedHashMap<String, ExternalItemSource>(sources);
        Map<String, SourceState> nextStates = new LinkedHashMap<String, SourceState>(states);
        for (ExternalItemSource source : additions) {
            Objects.requireNonNull(source, "source");
            String prefix = ItemRef.requirePrefix(source.prefix());
            if (ItemRef.VANILLA.equals(prefix)) {
                throw new IllegalArgumentException("Prefix 'minecraft' is reserved for vanilla materials");
            }
            nextSources.put(prefix, source);
            nextStates.put(prefix, new SourceState(prefix, source.plugin(), Status.AVAILABLE, "已注册"));
        }
        return new ExternalItems(nextSources, nextStates);
    }

    /** 每个前缀的挂钩状态，按注册顺序排列。 */
    public List<SourceState> report() {
        return Collections.unmodifiableList(new ArrayList<SourceState>(states.values()));
    }

    public boolean available(String prefix) {
        return sources.containsKey(prefix);
    }

    /**
     * 解析引用。前缀既未注册也不是内置前缀时按原版材质解析，材质不存在则抛出
     * {@link IllegalArgumentException}，便于在加载配置时发现拼写错误。
     */
    public ItemRef parse(String reference) {
        Objects.requireNonNull(reference, "reference");
        String text = reference.trim();
        int separator = text.indexOf(':');
        if (separator > 0) {
            String prefix = text.substring(0, separator).toLowerCase(java.util.Locale.ROOT);
            if (sources.containsKey(prefix) || states.containsKey(prefix) || BUILTIN_PLUGINS.containsKey(prefix)) {
                return ItemRef.of(prefix, text.substring(separator + 1));
            }
            if (!ItemRef.VANILLA.equals(prefix)) {
                throw new IllegalArgumentException("Unknown item source '" + prefix + "' in '" + reference
                        + "', known sources: " + knownPrefixes());
            }
        }
        return ItemRef.of(ItemRef.VANILLA, Items.resolveMaterial(text).name());
    }

    /** 生成物品。外部系统未安装或 ID 不存在时返回空。 */
    public Optional<ItemStack> create(String reference) {
        return create(parse(reference));
    }

    public Optional<ItemStack> create(String reference, int amount) {
        if (amount < 1) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        Optional<ItemStack> created = create(reference);
        created.ifPresent(item -> item.setAmount(amount));
        return created;
    }

    public Optional<ItemStack> create(ItemRef reference) {
        Objects.requireNonNull(reference, "reference");
        if (reference.vanilla()) {
            return Optional.of(new ItemStack(Items.resolveMaterial(reference.id())));
        }
        ExternalItemSource source = sources.get(reference.prefix());
        if (source == null) {
            return Optional.empty();
        }
        ItemStack item = source.create(reference.id());
        return InventoryItems.isAir(item) ? Optional.<ItemStack>empty() : Optional.of(item.clone());
    }

    /** 返回第一个认领该物品的外部系统引用；原版物品返回空。 */
    public Optional<ItemRef> identify(ItemStack item) {
        if (InventoryItems.isAir(item)) {
            return Optional.empty();
        }
        for (ExternalItemSource source : sources.values()) {
            String id = source.identify(item);
            if (id != null) {
                return Optional.of(ItemRef.of(source.prefix(), id));
            }
        }
        return Optional.empty();
    }

    public boolean matches(String reference, ItemStack item) {
        return matcher(reference).test(item);
    }

    /** 预先解析引用，返回可重复使用的匹配器。 */
    public Predicate<ItemStack> matcher(String reference) {
        ItemRef ref = parse(reference);
        if (ref.vanilla()) {
            Material material = Items.resolveMaterial(ref.id());
            return item -> !InventoryItems.isAir(item) && item.getType() == material && !identify(item).isPresent();
        }
        return item -> {
            if (InventoryItems.isAir(item)) {
                return false;
            }
            ExternalItemSource source = sources.get(ref.prefix());
            return source != null && ref.id().equals(source.identify(item));
        };
    }

    /** 转为 {@link ItemSpec}，数量保持外部物品自身的默认值。 */
    public ItemSpec spec(String reference) {
        ItemRef ref = parse(reference);
        if (ref.vanilla()) {
            return ItemSpec.builder().material(ref.id()).matching(matcher(reference)).build();
        }
        return ItemSpec.builder().external(ref.prefix(), ref.id(), this).build();
    }

    @Override
    public boolean matches(String provider, String id, ItemStack item) {
        ExternalItemSource source = sources.get(provider);
        return source != null && !InventoryItems.isAir(item) && id.equals(source.identify(item));
    }

    @Override
    public ItemStack create(String provider, String id) {
        return create(ItemRef.of(provider, id)).orElseThrow(() ->
                new IllegalStateException("External item is unavailable: " + provider + ":" + id));
    }

    private String knownPrefixes() {
        List<String> prefixes = new ArrayList<String>(sources.keySet());
        for (String builtin : BUILTIN_PLUGINS.keySet()) {
            if (!prefixes.contains(builtin)) {
                prefixes.add(builtin);
            }
        }
        prefixes.add(ItemRef.VANILLA);
        return String.join(", ", prefixes);
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getName() + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    /** 外部物品系统的挂钩状态。 */
    public enum Status {
        AVAILABLE,
        MISSING,
        FAILED
    }

    /** {@link #report()} 中的一行。 */
    public static final class SourceState {
        private final String prefix;
        private final String plugin;
        private final Status status;
        private final String detail;

        SourceState(String prefix, String plugin, Status status, String detail) {
            this.prefix = prefix;
            this.plugin = plugin;
            this.status = status;
            this.detail = detail;
        }

        public String prefix() {
            return prefix;
        }

        public String plugin() {
            return plugin;
        }

        public Status status() {
            return status;
        }

        public String detail() {
            return detail;
        }

        @Override
        public String toString() {
            return prefix + "(" + plugin + ")=" + status + (status == Status.AVAILABLE ? "" : ": " + detail);
        }
    }
}
