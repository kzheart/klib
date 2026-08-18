package me.kzheart.klib.lang;

import org.bukkit.entity.Player;

interface BukkitComponentSender {
    boolean send(Player player, RichText message, boolean actionBar);
}
