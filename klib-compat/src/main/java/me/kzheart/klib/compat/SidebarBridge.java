package me.kzheart.klib.compat;

import org.bukkit.entity.Player;

/**
 * 按玩家发送侧边栏数据包的版本能力。
 *
 * <p>实现只向目标玩家的客户端发送记分板数据包，不读取或替换玩家的 Bukkit {@code Scoreboard}，
 * 也不保存玩家状态；差异计算、生命周期和退出清理由
 * {@link me.kzheart.klib.compat.sidebar.Sidebars} 负责，业务代码通常不直接调用本接口。
 *
 * <p>行号 {@code index} 从侧边栏底部的 {@code 0} 开始，同时作为该行的分数。标题和行文本使用
 * {@code §} 颜色代码（包括 {@code §x} 十六进制颜色）。{@code objective} 由调用方保证在同一客户端内
 * 唯一且不超过 16 个字符；每行使用的队伍名和计分项持有者由实现从 {@code objective} 与 {@code index}
 * 派生。
 */
public interface SidebarBridge {
    /** 客户端可以完整显示的最大行数。 */
    int maxLines();

    /** 创建计分项并显示到侧边栏槽位。 */
    void create(Player player, String objective, String title);

    /** 修改已创建计分项的标题。 */
    void title(Player player, String objective, String title);

    /** 在 {@code index} 处新增一行。 */
    void addLine(Player player, String objective, int index, String text);

    /** 修改 {@code index} 处已存在行的文本。 */
    void updateLine(Player player, String objective, int index, String text);

    /** 移除 {@code index} 处的行。 */
    void removeLine(Player player, String objective, int index);

    /** 移除计分项，并清理此前 {@code lineCount} 行占用的客户端状态。 */
    void remove(Player player, String objective, int lineCount);
}
