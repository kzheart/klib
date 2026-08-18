package me.kzheart.klib.ui;

import me.kzheart.klib.scheduler.TaskHandle;
import me.kzheart.klib.scheduler.Ticks;
import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.scope.Scope;
import me.kzheart.klib.ui.drop.DropZoneController;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** 通过子作用域持有菜单刷新任务和临时物品。 */
public final class MenuSession implements Disposable {
    private static final AtomicLong SESSION_IDS = new AtomicLong();
    private final Scope parent;
    private final Scope scope;
    private final MenuModel model;
    private final ItemReturnTarget returnTarget;
    private final OverflowSink overflowSink;
    private final List<DropZoneController> dropZones = new ArrayList<DropZoneController>();
    private boolean closing;
    private MenuCloseResult closeResult;

    private MenuSession(
            Scope parent,
            Scope scope,
            MenuModel model,
            ItemReturnTarget returnTarget,
            OverflowSink overflowSink
    ) {
        this.parent = Objects.requireNonNull(parent, "parent");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.model = Objects.requireNonNull(model, "model");
        this.returnTarget = Objects.requireNonNull(returnTarget, "returnTarget");
        this.overflowSink = overflowSink;
    }

    public static MenuSession open(
            Scope parent,
            String name,
            MenuModel model,
            ItemReturnTarget returnTarget
    ) {
        return open(parent, name, model, returnTarget, null);
    }

    /**
     * 打开带 {@link OverflowSink} 兜底的会话：当归还目标抛出异常或报告溢出时，
     * 剩余物品会交给接收器，而不是以异常形式暴露。
     */
    public static MenuSession open(
            Scope parent,
            String name,
            MenuModel model,
            ItemReturnTarget returnTarget,
            OverflowSink overflowSink
    ) {
        Objects.requireNonNull(parent, "parent");
        String businessName = Objects.requireNonNull(name, "name").trim();
        if (businessName.isEmpty()) {
            throw new IllegalArgumentException("menu name must not be blank");
        }
        Scope menuScope = parent.scope(
                "menu:" + businessName + "#" + Long.toUnsignedString(SESSION_IDS.incrementAndGet()),
                scope -> { });
        return parent.install(new MenuSession(parent, menuScope, model, returnTarget, overflowSink));
    }

    public MenuModel model() {
        return model;
    }

    /** 持有此菜单监听器、任务和投放区的作用域。 */
    public Scope scope() {
        return scope;
    }

    public synchronized DropZoneController addDropZone(DropZoneController controller) {
        ensureOpen();
        if (overflowSink == null) {
            throw new IllegalStateException(
                    "drop zones require an OverflowSink so scope shutdown cannot lose items");
        }
        Objects.requireNonNull(controller, "controller");
        for (Integer slot : controller.slots()) {
            if (slot.intValue() >= model.size()) {
                throw new IllegalArgumentException("drop-zone slot is outside menu: " + slot);
            }
            if (model.entry(slot.intValue()).isPresent()) {
                throw new IllegalArgumentException("drop-zone slot overlaps menu entry: " + slot);
            }
            for (DropZoneController present : dropZones) {
                if (present.slots().contains(slot)) {
                    throw new IllegalArgumentException("drop-zone slot is already owned: " + slot);
                }
            }
        }
        dropZones.add(controller);
        return controller;
    }

    /** 已注册投放区的快照。 */
    public synchronized List<DropZoneController> dropZones() {
        return new ArrayList<DropZoneController>(dropZones);
    }

    /** 覆盖给定顶部物品栏槽位的投放区（若存在）。 */
    public synchronized Optional<DropZoneController> dropZoneAt(int slot) {
        Integer key = Integer.valueOf(slot);
        for (DropZoneController zone : dropZones) {
            if (zone.slots().contains(key)) {
                return Optional.of(zone);
            }
        }
        return Optional.empty();
    }

    public synchronized TaskHandle refreshEvery(Ticks period, Runnable refresh) {
        ensureOpen();
        return scope.every(period, Objects.requireNonNull(refresh, "refresh"));
    }

    /**
     * 在释放菜单作用域前归还全部临时物品。归还目标和作用域拆除会在本会话监视器之外运行，
     * 因此外部回调不会与菜单状态形成死锁。
     */
    public MenuCloseResult close(CloseReason reason) {
        Objects.requireNonNull(reason, "reason");
        List<DropZoneController> zones;
        synchronized (this) {
            while (closing) {
                if (closeResult != null) {
                    return closeResult;
                }
                try {
                    wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while closing menu", interrupted);
                }
            }
            if (closeResult != null) {
                return closeResult;
            }
            closing = true;
            zones = new ArrayList<DropZoneController>(dropZones);
        }

        List<ItemStack> owned = new ArrayList<ItemStack>();
        for (DropZoneController dropZone : zones) {
            owned.addAll(dropZone.closeAndDrain());
        }
        List<ItemStack> overflow = cloneItems(owned);
        RuntimeException returnFailure = null;
        try {
            if (!owned.isEmpty()) {
                overflow = Objects.requireNonNull(returnTarget.returnItems(cloneItems(owned)),
                        "return target result");
            }
        } catch (RuntimeException failure) {
            if (overflowSink == null) {
                returnFailure = failure;
            } else {
                // 有兜底时不重抛：全部物品并入 unreturned 走 sink。
                overflow = cloneItems(owned);
            }
        }
        if (overflowSink != null && !overflow.isEmpty()) {
            try {
                overflow = Objects.requireNonNull(
                        overflowSink.returnItems(cloneItems(overflow)),
                        "overflow sink result");
            } catch (RuntimeException sinkFailure) {
                // 接收器原子地未接收任何物品；在 MenuCloseResult 中保留准确溢出内容，
                // 使调用方可以恢复。
            }
        }

        MenuCloseResult result = new MenuCloseResult(reason, overflow);
        synchronized (this) {
            closeResult = result;
            closing = false;
            notifyAll();
        }
        // 从父作用域自摘，避免长生命周期作用域累积已结束的会话；
        // 父作用域正在拆除时 Scope.remove 静默返回，因此 dispose 路径同样安全。
        parent.remove(this);
        try {
            scope.close();
        } catch (RuntimeException closeFailure) {
            if (returnFailure != null) {
                returnFailure.addSuppressed(closeFailure);
            } else {
                throw closeFailure;
            }
        }
        if (returnFailure != null) {
            throw returnFailure;
        }
        return result;
    }

    public synchronized boolean isClosed() {
        return closeResult != null;
    }

    @Override
    public void dispose() {
        close(CloseReason.SCOPE_CLOSED);
    }

    private void ensureOpen() {
        if (closing || closeResult != null || scope.isClosed()) {
            throw new IllegalStateException("menu session is closed");
        }
    }

    private static List<ItemStack> cloneItems(List<ItemStack> source) {
        List<ItemStack> copy = new ArrayList<ItemStack>(source.size());
        for (ItemStack item : source) {
            copy.add(item.clone());
        }
        return copy;
    }
}
