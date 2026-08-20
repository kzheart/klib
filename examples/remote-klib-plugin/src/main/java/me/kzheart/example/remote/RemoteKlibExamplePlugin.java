package me.kzheart.example.remote;

import me.kzheart.klib.KPlugin;
import me.kzheart.klib.command.BukkitCommandRegistrar;
import me.kzheart.klib.command.CommandModule;
import me.kzheart.klib.scheduler.Ticks;
import me.kzheart.klib.scope.Scope;

public final class RemoteKlibExamplePlugin extends KPlugin {
    @Override
    protected void setup(Scope root) {
        CommandModule.install(root, BukkitCommandRegistrar.discover("remoteklibexample"));

        GreetingSession greeting = root.install(new GreetingSession(logger()));
        root.command("klibhello", command -> command
                .description("Send a greeting built with Klib")
                .executes(context -> greeting.sendTo(context.sender())));

        root.after(Ticks.of(1L), () -> logger().success("Remote Klib example is ready"));
    }
}

