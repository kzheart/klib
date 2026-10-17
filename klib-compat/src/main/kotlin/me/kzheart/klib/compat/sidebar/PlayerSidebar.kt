/*
 * 差异更新流程改写自 TabooLib（https://github.com/TabooLib/taboolib）的
 * module/bukkit-nms/bukkit-nms-stable/src/main/kotlin/taboolib/module/nms/type/PlayerScoreboard.kt
 * 与 NMSScoreboardImpl.kt 的 changeContent/updateLineCount。
 *
 * Copyright (c) 2018 Bkm016
 * Licensed under the MIT License; the full license text is reproduced in THIRD_PARTY_NOTICES.md
 * and META-INF/LICENSE-TabooLib-Scoreboard.txt. Source revision: 0e3a911fc55624075b5c9abd4368cb5b063b022b.
 */
package me.kzheart.klib.compat.sidebar

import me.kzheart.klib.compat.SidebarBridge
import org.bukkit.entity.Player

/** Per-player acknowledged packet state; failed sends never advance the corresponding state. */
internal class PlayerSidebar(private val bridge: SidebarBridge, private val objective: String, private val player: Player) {
    private val lines = ArrayList<String>()
    private var title: String? = null
    private var created = false
    fun belongsTo(candidate: Player): Boolean = player === candidate
    fun show(newTitle: String, topDown: List<String>) {
        if (!created) {
            bridge.create(player, objective, newTitle)
            created = true
            title = newTitle
        } else if (title != newTitle) {
            bridge.title(player, objective, newTitle)
            title = newTitle
        }
        while (lines.size > topDown.size) {
            val index = lines.lastIndex
            bridge.removeLine(player, objective, index)
            lines.removeAt(index)
        }
        topDown.asReversed().forEachIndexed { index, text ->
            if (index >= lines.size) {
                bridge.addLine(player, objective, index, text)
                lines.add(text)
            } else if (lines[index] != text) {
                bridge.updateLine(player, objective, index, text)
                lines[index] = text
            }
        }
    }
    fun hide() {
        if (!created) return
        val count = lines.size
        created = false
        lines.clear()
        if (player.isOnline) bridge.remove(player, objective, count)
    }
}
