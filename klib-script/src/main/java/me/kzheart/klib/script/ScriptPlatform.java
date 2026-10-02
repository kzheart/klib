package me.kzheart.klib.script;

import java.util.List;

/**
 * 原框架游戏语句使用的服务器能力：广播、标题、音效、坐标、物品与侧边栏。
 * 参数中的玩家与返回的对象由宿主平台决定（Bukkit 下为 Player、Location、ItemStack、Material）。
 */
public interface ScriptPlatform {

    List<String> onlinePlayerNames();

    void broadcast(String message);

    /** 控制台发送者，{@code switch console} 使用。 */
    Object console();

    /** 按名称查找在线玩家，找不到返回 null。 */
    Object player(String name);

    void actionBar(Object player, String message);

    void title(Object player, String title, String subtitle, int fadeIn, int stay, int fadeOut);

    /** sound 以 {@code resource:} 开头时为资源包音效名，否则为枚举名。 */
    void playSound(Object player, String sound, float volume, float pitch);

    void stopSound(Object player, String sound);

    Object location(String world, double x, double y, double z, float yaw, float pitch);

    Object material(String name);

    Object itemStack(String material);

    /** 显示侧边栏，lines 首行为标题；lines 为 null 时移除。默认不支持。 */
    default void scoreboard(Object player, List<String> lines) {
        throw new UnsupportedOperationException("scoreboard is not supported by this host");
    }
}
