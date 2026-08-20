package me.kzheart.example.remote;

import me.kzheart.klib.KLogger;
import me.kzheart.klib.scope.Disposable;
import org.bukkit.command.CommandSender;

final class GreetingSession implements Disposable {
    private final KLogger logger;

    GreetingSession(KLogger logger) {
        this.logger = logger;
        logger.info("Greeting session started");
    }

    void sendTo(CommandSender sender) {
        sender.sendMessage("Hello from a remotely resolved Klib plugin!");
    }

    @Override
    public void dispose() {
        logger.info("Greeting session stopped");
    }
}
