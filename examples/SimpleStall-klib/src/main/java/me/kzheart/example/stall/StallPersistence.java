package me.kzheart.example.stall;

import me.kzheart.klib.KLogger;
import me.kzheart.klib.data.StorageProvider;
import me.kzheart.klib.data.StorageSession;
import me.kzheart.klib.data.json.JsonStorageProvider;
import me.kzheart.klib.scope.Disposable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/** M3 测试服务器使用的异步 JSON 键值存储边界。 */
public final class StallPersistence implements Disposable {
    private static final String NAMESPACE = "stall-listings";

    private final StorageProvider provider;
    private final CompletionStage<StorageSession> session;
    private final KLogger logger;
    private final AtomicBoolean closed = new AtomicBoolean();

    public StallPersistence(Path file, KLogger logger) {
        this.provider = new JsonStorageProvider(file);
        this.session = provider.open();
        this.logger = logger;
        session.whenComplete((opened, error) -> {
            if (error == null) {
                logger.info("[klib-m3] storage-ready provider=json");
            } else {
                logger.error("M3 JSON 存储初始化失败", error);
            }
        });
    }

    public void save(StallListing listing) {
        String encoded = listing.sellerId() + "|"
                + listing.sellerName() + "|"
                + listing.material() + "|"
                + listing.amount() + "|"
                + listing.terms().sellerUnitPrice().toPlainString() + "|"
                + listing.terms().priceType().name();
        session.thenCompose(opened -> opened.put(
                NAMESPACE,
                Long.toString(listing.id()),
                encoded.getBytes(StandardCharsets.UTF_8)))
                .whenComplete((ignored, error) -> report("write", listing.id(), error));
    }

    public void remove(long listingId) {
        session.thenCompose(opened -> opened.delete(NAMESPACE, Long.toString(listingId)))
                .whenComplete((ignored, error) -> report("delete", listingId, error));
    }

    private void report(String operation, long listingId, Throwable error) {
        if (error == null) {
            logger.info("[klib-m3] storage-" + operation + "-ok id=" + listingId);
        } else if (!closed.get()) {
            logger.error("M3 JSON 存储操作失败: " + operation + " id=" + listingId, error);
        }
    }

    @Override
    public void dispose() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        session.whenComplete((opened, error) -> {
            if (opened != null) {
                opened.dispose();
            }
            provider.dispose();
        });
    }
}
