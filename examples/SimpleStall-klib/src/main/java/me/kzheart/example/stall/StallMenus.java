package me.kzheart.example.stall;

import me.kzheart.klib.KLogger;
import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.scope.Scope;
import me.kzheart.klib.ui.MenuCompiler;
import me.kzheart.klib.ui.MenuEntry;
import me.kzheart.klib.ui.MenuModel;
import me.kzheart.klib.ui.MenuRenderer;
import me.kzheart.klib.ui.Paginator;
import me.kzheart.klib.ui.prompt.BukkitChatPrompts;
import me.kzheart.klib.ui.prompt.PromptSpec;
import me.kzheart.klib.ui.prompt.PromptSession;
import me.kzheart.klib.ui.prompt.PromptStatus;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 由 klib-ui 菜单模型、会话、分页和提示输入支持的 Bukkit 渲染器。 */
public final class StallMenus implements Disposable {
    private static final int ADD_SLOT = 49;

    private final StallRuntime runtime;
    private final StallSettings settings;
    private final KLogger logger;
    private final MenuRenderer menuRenderer;
    private final BukkitChatPrompts chatPrompts;

    public StallMenus(
            JavaPlugin plugin,
            Scope scope,
            StallRuntime runtime,
            StallSettings settings,
            KLogger logger
    ) {
        this.runtime = runtime;
        this.settings = settings;
        this.logger = logger;
        this.menuRenderer = MenuRenderer.install(scope, plugin);
        this.chatPrompts = BukkitChatPrompts.install(scope, plugin);
    }

    public void openManage(Player player) {
        String title = color("&6商品管理 第1页");
        Map<Integer, MenuEntry> entries = new LinkedHashMap<Integer, MenuEntry>();
        entries.put(Integer.valueOf(ADD_SLOT), MenuEntry.of(
                named(Material.EMERALD, "&a添加商品", "&7点击上架手中全部物品"),
                click -> beginListingPrompt(player)));
        List<StallListing> listings = runtime.bySeller(player.getUniqueId());
        renderListings(entries, listings, false, player);
        open(player, title, MenuKind.MANAGE, entries);
    }

    public void openShop(Player player, String sellerName) {
        List<StallListing> listings = runtime.bySellerName(sellerName);
        String title = color("&6" + sellerName + " 的商店 第1页");
        Map<Integer, MenuEntry> entries = new LinkedHashMap<Integer, MenuEntry>();
        renderListings(entries, listings, true, player);
        open(player, title, MenuKind.BUY, entries);
    }

    private void renderListings(
            Map<Integer, MenuEntry> entries,
            List<StallListing> listings,
            boolean buy,
            Player player
    ) {
        int slot = 10;
        for (StallListing listing : new Paginator<StallListing>(listings, 28).page(0).values()) {
            Material material = Material.matchMaterial(listing.material());
            if (material == null) {
                material = Material.STONE;
            }
            ItemStack item = new ItemStack(material, Math.min(64, listing.amount()));
            String action = buy ? "&a点击购买" : "&c点击下架";
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(color("&f" + listing.material()));
                List<String> lore = new ArrayList<String>();
                lore.add(color("&7单价: &e" + listing.terms().listedUnitPrice().toPlainString()));
                lore.add(color("&7数量: &e" + listing.amount()));
                lore.add(color(action));
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
            final long listingId = listing.id();
            entries.put(Integer.valueOf(slot), buy
                    ? MenuEntry.of(item, click -> beginPurchasePrompt(player, listingId))
                    : MenuEntry.of(item));
            slot = nextContentSlot(slot);
        }
    }

    private void open(
            Player player,
            String title,
            MenuKind kind,
            Map<Integer, MenuEntry> entries
    ) {
        MenuModel model = MenuCompiler.compileSlots(title, 6, entries);
        menuRenderer.open(
                player,
                player.getUniqueId() + ":" + kind.name().toLowerCase(Locale.ROOT),
                model);
    }

    private void beginListingPrompt(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType() == Material.AIR || held.getAmount() < 1) {
            player.sendMessage(color("&c请手持要上架的物品"));
            return;
        }
        PromptSession<String> session = chatPrompts.start(player, PromptSpec
                .builder(StallMenus::validListingInput)
                .invalidMessage(color("&c价格格式错误，请使用: &f价格 类型"))
                .cancelledMessage(color("&e已取消上架"))
                .timeoutMessage(color("&c输入已超时"))
                .build());
        beginPrompt(player, PendingPrompt.list(held.clone(), session));
        logger.info("[klib-m3] prompt-open type=list player=" + player.getName());
        player.closeInventory();
        player.sendMessage(color("&a请输入价格和类型(money/points)，例如: &f100 money"));
    }

    private void beginPurchasePrompt(Player player, long listingId) {
        StallListing listing = runtime.find(listingId);
        if (listing == null) {
            player.sendMessage(color("&c该商品已不存在"));
            return;
        }
        PromptSession<String> session = chatPrompts.start(player, PromptSpec
                .builder(input -> validPurchaseAmount(input, listing.amount()))
                .invalidMessage(color("&c请输入 1 到 " + listing.amount() + " 之间的整数"))
                .cancelledMessage(color("&e已取消购买"))
                .timeoutMessage(color("&c输入已超时"))
                .build());
        beginPrompt(player, PendingPrompt.buy(listing.id(), session));
        logger.info("[klib-m3] prompt-open type=buy player=" + player.getName());
        player.closeInventory();
        player.sendMessage(color("&a请输入购买数量 (最大: &f" + listing.amount() + "&a)"));
    }

    private void beginPrompt(Player player, PendingPrompt prompt) {
        prompt.session.completionSync().thenAccept(outcome -> {
            if (outcome.status() == PromptStatus.CANCELLED) {
                return;
            }
            if (!player.isOnline()) {
                return;
            }
            if (outcome.status() == PromptStatus.ANSWERED) {
                completePrompt(player, prompt, outcome.value().get());
            }
        });
    }

    private static Optional<String> validListingInput(String input) {
        String[] parts = input.trim().split("\\s+");
        if (parts.length != 2) {
            return Optional.empty();
        }
        try {
            BigDecimal price = new BigDecimal(parts[0]);
            PriceType.valueOf(parts[1].toUpperCase(Locale.ROOT));
            return price.signum() > 0 ? Optional.of(input) : Optional.empty();
        } catch (IllegalArgumentException failure) {
            return Optional.empty();
        }
    }

    private static Optional<String> validPurchaseAmount(String input, int maximum) {
        try {
            int amount = Integer.parseInt(input.trim());
            return amount >= 1 && amount <= maximum
                    ? Optional.of(input)
                    : Optional.<String>empty();
        } catch (NumberFormatException failure) {
            return Optional.empty();
        }
    }

    private void completePrompt(Player player, PendingPrompt prompt, String message) {
        if (!player.isOnline()) {
            return;
        }
        if (prompt.kind == PromptKind.LIST) {
            completeListing(player, prompt.item, message);
        } else {
            completePurchase(player, prompt.listingId, message);
        }
    }

    private void completeListing(Player player, ItemStack snapshot, String input) {
        String[] parts = input.trim().split("\\s+");
        if (parts.length != 2) {
            player.sendMessage(color("&c价格格式错误，请使用: &f价格 类型"));
            return;
        }
        final BigDecimal sellerPrice;
        final PriceType priceType;
        try {
            sellerPrice = new BigDecimal(parts[0]);
            priceType = PriceType.valueOf(parts[1].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            player.sendMessage(color("&c无效的价格或类型"));
            return;
        }
        double configuredTax = priceType == PriceType.MONEY
                ? settings.moneyTax
                : settings.pointsTax;
        final ListingTerms terms;
        try {
            terms = ListingTerms.of(
                    sellerPrice, BigDecimal.valueOf(configuredTax), priceType);
        } catch (IllegalArgumentException failure) {
            player.sendMessage(color("&c无效的价格"));
            return;
        }

        ItemStack current = player.getInventory().getItemInMainHand();
        if (current == null || current.getAmount() < snapshot.getAmount()
                || !current.isSimilar(snapshot)) {
            player.sendMessage(color("&c物品不存在或数量不足"));
            return;
        }
        int remaining = current.getAmount() - snapshot.getAmount();
        if (remaining == 0) {
            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        } else {
            current.setAmount(remaining);
        }
        StallListing listing = runtime.list(
                player.getUniqueId(),
                player.getName(),
                snapshot.getType().name(),
                snapshot.getAmount(),
                terms);
        player.sendMessage(color("&a物品已上架，买家单价: &f"
                + terms.listedUnitPrice().toPlainString()));
        logger.info("[klib-m3] listing behavior-ok id=" + listing.id()
                + " amount=" + listing.amount());
    }

    private void completePurchase(Player player, long listingId, String input) {
        final int amount;
        try {
            amount = Integer.parseInt(input.trim());
        } catch (NumberFormatException failure) {
            player.sendMessage(color("&c无效的数量"));
            return;
        }
        StallListing before = runtime.find(listingId);
        PurchaseResult result = runtime.purchase(listingId, player.getUniqueId(), amount);
        if (result.status() != PurchaseResult.Status.COMPLETED || before == null) {
            player.sendMessage(color("&c交易失败: " + result.status().name()));
            return;
        }
        Material material = Material.matchMaterial(before.material());
        if (material == null) {
            throw new IllegalStateException("Unknown listing material: " + before.material());
        }
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(
                new ItemStack(material, amount));
        for (ItemStack item : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), item);
        }
        if (overflow.isEmpty() && !player.getInventory().contains(material, amount)) {
            throw new IllegalStateException("Purchased items were not delivered to the buyer");
        }
        player.sendMessage(color("&a购买成功！花费: &f"
                + result.charged().toPlainString() + " 金币"));
        logger.info("[klib-m3] purchase behavior-ok delivery-ok id=" + listingId
                + " amount=" + amount
                + " remaining=" + result.remainingStock()
                + " charged=" + result.charged().toPlainString()
                + " income=" + result.sellerIncome().toPlainString());
    }

    public StallListing seedFixture(Player buyer) {
        UUID sellerId = UUID.nameUUIDFromBytes(
                "klib-m3-seller".getBytes(StandardCharsets.UTF_8));
        runtime.ledger().set(buyer.getUniqueId(), new BigDecimal("1000"));
        runtime.ledger().set(sellerId, BigDecimal.ZERO);
        return runtime.list(
                sellerId,
                "M3Seller",
                Material.DIAMOND.name(),
                4,
                ListingTerms.of(
                        new BigDecimal("100"),
                        BigDecimal.valueOf(settings.moneyTax),
                        PriceType.MONEY));
    }

    @Override
    public void dispose() {
        chatPrompts.dispose();
        menuRenderer.dispose();
    }

    private static int nextContentSlot(int current) {
        int next = current + 1;
        if (next % 9 == 8) {
            next += 2;
        }
        return next;
    }

    private static ItemStack named(Material material, String name, String loreLine) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            List<String> lore = new ArrayList<String>();
            lore.add(color(loreLine));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    private enum MenuKind {
        MANAGE,
        BUY
    }

    private enum PromptKind {
        LIST,
        BUY
    }

    private static final class PendingPrompt {
        private final PromptKind kind;
        private final ItemStack item;
        private final long listingId;
        private final PromptSession<String> session;

        private PendingPrompt(
                PromptKind kind,
                ItemStack item,
                long listingId,
                PromptSession<String> session
        ) {
            this.kind = kind;
            this.item = item;
            this.listingId = listingId;
            this.session = session;
        }

        private static PendingPrompt list(ItemStack item, PromptSession<String> session) {
            return new PendingPrompt(PromptKind.LIST, item, 0L, session);
        }

        private static PendingPrompt buy(long listingId, PromptSession<String> session) {
            return new PendingPrompt(PromptKind.BUY, null, listingId, session);
        }
    }
}
