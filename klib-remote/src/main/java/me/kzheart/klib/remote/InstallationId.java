package me.kzheart.klib.remote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** 每商品独立、只由本地安全随机数生成并持久化的匿名安装标识。 */
public final class InstallationId implements Supplier<String> {
    private static final Logger LOGGER = Logger.getLogger(InstallationId.class.getName());
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int RANDOM_BYTES = 16;
    static final String DIRECTORY_NAME = ".klib-remote";

    private final Path cacheFile;
    private volatile String cached;

    private InstallationId(Path cacheFile) {
        this.cacheFile = cacheFile;
    }

    /**
     * 返回一个商品作用域的 installation id。
     *
     * <p>不读取 IP、MAC、hostname、硬件、端口或路径内容。相同商品与数据目录跨重启稳定；
     * 不同商品使用不同文件；删除插件数据目录后生成新标识。
     */
    public static InstallationId forProduct(String productId, Path dataDirectory) {
        String product = Texts.requireText(productId, "productId");
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        if (product.getBytes(StandardCharsets.UTF_8).length > 512) {
            throw new IllegalArgumentException("productId is too long");
        }
        return new InstallationId(dataDirectory.resolve(DIRECTORY_NAME)
                .resolve("installation-" + productFileKey(product)));
    }

    @Override public String get() {
        String value = cached;
        if (value != null) return value;
        synchronized (this) {
            if (cached != null) return cached;
            String loaded = read();
            if (loaded == null) {
                loaded = randomValue();
                write(loaded);
            }
            cached = loaded;
            return loaded;
        }
    }

    private String read() {
        if (!Files.isRegularFile(cacheFile, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            String value = new String(Files.readAllBytes(cacheFile), StandardCharsets.UTF_8).trim();
            return valid(value) ? value : null;
        } catch (IOException failure) {
            LOGGER.log(Level.FINE, "Unable to read Remote installation id", failure);
            return null;
        }
    }

    private void write(String value) {
        Path directory = cacheFile.getParent();
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, "installation-", ".tmp");
            try {
                Files.write(temporary, value.getBytes(StandardCharsets.UTF_8));
                Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException failure) {
            LOGGER.log(Level.WARNING, "Unable to persist Remote installation id", failure);
        }
    }

    private static String randomValue() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        StringBuilder result = new StringBuilder("inst_");
        for (byte value : bytes) {
            result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    static String productFileKey(String product) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(product.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(32);
            for (int index = 0; index < 16; index++) {
                result.append(String.format(java.util.Locale.ROOT, "%02x", digest[index] & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean valid(String value) {
        if (value == null || !value.startsWith("inst_") || value.length() != 37) return false;
        for (int index = 5; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f'))) return false;
        }
        return true;
    }
}
