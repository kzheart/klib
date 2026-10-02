/*
 * 差异更新流程改写自 TabooLib（https://github.com/TabooLib/taboolib）的
 * module/bukkit-nms/bukkit-nms-stable/src/main/kotlin/taboolib/module/nms/type/PlayerScoreboard.kt
 * 与 NMSScoreboardImpl.kt 的 changeContent/updateLineCount。
 *
 * Copyright (c) 2018 Bkm016
 * Licensed under the MIT License; the full license text is reproduced in THIRD_PARTY_NOTICES.md
 * and META-INF/LICENSE-TabooLib-Scoreboard.txt. Source revision: 0e3a911fc55624075b5c9abd4368cb5b063b022b.
 */
package me.kzheart.klib.compat.sidebar;

import me.kzheart.klib.compat.SidebarBridge;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个玩家客户端上的侧边栏状态。行按分数索引保存：索引 0 是最底部一行。
 *
 * <p>调用方负责串行化同一实例的访问。
 */
final class PlayerSidebar {
    private final SidebarBridge bridge;
    private final String objective;
    private final Player player;
    private final List<String> lines = new ArrayList<String>();
    private String title;
    private boolean created;

    PlayerSidebar(SidebarBridge bridge, String objective, Player player) {
        this.bridge = bridge;
        this.objective = objective;
        this.player = player;
    }

    boolean belongsTo(Player candidate) {
        return player == candidate;
    }

    /** {@code topDown} 自上而下排列。 */
    void show(String newTitle, List<String> topDown) {
        if (!created) {
            bridge.create(player, objective, newTitle);
            created = true;
            title = newTitle;
        } else if (!title.equals(newTitle)) {
            bridge.title(player, objective, newTitle);
            title = newTitle;
        }
        int size = topDown.size();
        while (lines.size() > size) {
            int index = lines.size() - 1;
            bridge.removeLine(player, objective, index);
            lines.remove(index);
        }
        for (int index = 0; index < size; index++) {
            String text = topDown.get(size - 1 - index);
            if (index >= lines.size()) {
                bridge.addLine(player, objective, index, text);
                lines.add(text);
            } else if (!lines.get(index).equals(text)) {
                bridge.updateLine(player, objective, index, text);
                lines.set(index, text);
            }
        }
    }

    /** 向仍在线的玩家移除侧边栏。 */
    void hide() {
        if (!created) {
            return;
        }
        int lineCount = lines.size();
        created = false;
        lines.clear();
        if (player.isOnline()) {
            bridge.remove(player, objective, lineCount);
        }
    }
}
