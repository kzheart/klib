package me.kzheart.example.cloud;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import me.kzheart.klib.guard.KlibCloudPlugin;
import me.kzheart.klib.scope.Scope;
import org.bukkit.ChatColor;
import org.bukkit.event.player.PlayerJoinEvent;

public final class RemoteCloudExample extends KlibCloudPlugin {

    private static final String STATS_FILE = "stats.properties";
    private static final String TOTAL_JOINS_KEY = "totalJoins";

    private int totalJoins;

    @Override
    protected void load() throws IOException {
        Files.createDirectories(dataFolder().toPath());
        totalJoins = loadTotalJoins(dataFolder().toPath().resolve(STATS_FILE));
        logger().info("已加载商品 " + host().productId() + "，累计加入次数: " + totalJoins);
    }

    @Override
    protected void setup(Scope root) {
        root.on(PlayerJoinEvent.class, this::welcomePlayer);
        logger().info("玩家欢迎业务已就绪");
    }

    @Override
    protected void disable() throws IOException {
        saveTotalJoins(dataFolder().toPath().resolve(STATS_FILE), totalJoins);
        logger().info("已保存累计加入次数: " + totalJoins);
    }

    private void welcomePlayer(PlayerJoinEvent event) {
        totalJoins++;
        event.getPlayer().sendMessage(
                ChatColor.AQUA + "欢迎回来，" + event.getPlayer().getName()
                        + "！这是本商品记录的第 " + totalJoins + " 次加入。");
    }

    private static int loadTotalJoins(Path path) throws IOException {
        if (!Files.exists(path)) {
            return 0;
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }

        String value = properties.getProperty(TOTAL_JOINS_KEY, "0");
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new IOException("Invalid negative totalJoins: " + value);
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IOException("Invalid totalJoins: " + value, failure);
        }
    }

    private static void saveTotalJoins(Path path, int value) throws IOException {
        Properties properties = new Properties();
        properties.setProperty(TOTAL_JOINS_KEY, Integer.toString(value));

        Path temporary = path.resolveSibling(path.getFileName().toString() + ".tmp");
        try (OutputStream output = Files.newOutputStream(temporary)) {
            properties.store(output, "Remote Klib cloud plugin statistics");
        }
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
    }
}
