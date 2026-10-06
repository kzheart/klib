package me.kzheart.klib.ui.chat;

import java.util.function.Consumer;
import org.bukkit.entity.Player;

/** 告示牌输入实现。所有方法在主线程调用，回调也必须在主线程触发。 */
public interface ChatPanelSignInput {
    /**
     * 为玩家打开一块预填四行文字的告示牌。
     *
     * @return 无法打开时返回 false，面板会改用聊天输入
     */
    boolean open(Player player, String[] lines, Consumer<String[]> submitted);

    /** 放弃玩家当前的告示牌输入；之后的提交不再回调。 */
    void cancel(Player player);
}
