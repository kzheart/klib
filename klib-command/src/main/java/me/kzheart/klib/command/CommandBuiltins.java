package me.kzheart.klib.command;

import me.kzheart.klib.command.api.CommandContext;
import me.kzheart.klib.command.api.CommandSpec;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;

public final class CommandBuiltins {
    /** 显式声明内置子命令不需要权限（区别于传 null 的疏忽）。 */
    public static final String PERMISSION_NONE = "<none>";

    private static final Logger LOGGER = Logger.getLogger(CommandBuiltins.class.getName());
    private static final String DEFAULT_ADMIN_PERMISSION = "klib.command.builtin.admin";

    private boolean helpEnabled = true;
    private String helpPermission;
    private Runnable reloadAction;
    private Supplier<? extends CompletionStage<?>> reloadAsyncAction;
    private String reloadPermission;
    private BooleanSupplier debugState;
    private Consumer<Boolean> debugAction;
    private String debugPermission;

    private CommandBuiltins() {
    }

    public static CommandBuiltins create() {
        return new CommandBuiltins();
    }

    /**
     * 不推荐：reload/debug 应显式指定权限。本重载默认拒绝——要求
     * {@code klib.command.builtin.admin}（未声明的权限节点默认仅 OP），
     * 请改用 {@link #standard(String, Runnable, BooleanSupplier, Consumer)}。
     */
    public static CommandBuiltins standard(
            Runnable reloadAction,
            BooleanSupplier debugState,
            Consumer<Boolean> debugAction
    ) {
        LOGGER.warning("CommandBuiltins.standard(...) 未指定权限，reload/debug 默认要求 "
                + DEFAULT_ADMIN_PERMISSION + "；请改用带权限参数的重载");
        return standard(DEFAULT_ADMIN_PERMISSION, reloadAction, debugState, debugAction);
    }

    /** 标准内置命令；重新加载确认消息会等待异步监听器完成。 */
    public static CommandBuiltins standardAsync(
            String permission,
            Supplier<? extends CompletionStage<?>> reloadAction,
            BooleanSupplier debugState,
            Consumer<Boolean> debugAction
    ) {
        if (permission == null) {
            throw new NullPointerException(
                    "permission（不需要权限请显式传 CommandBuiltins.PERMISSION_NONE）");
        }
        return create()
                .reloadAsync(permission, reloadAction)
                .debug(permission, debugState, debugAction);
    }

    /**
     * 带显式权限的 standard 内置集；确实不需要权限时传 {@link #PERMISSION_NONE}。
     */
    public static CommandBuiltins standard(
            String permission,
            Runnable reloadAction,
            BooleanSupplier debugState,
            Consumer<Boolean> debugAction
    ) {
        if (permission == null) {
            throw new NullPointerException(
                    "permission（不需要权限请显式传 CommandBuiltins.PERMISSION_NONE）");
        }
        return create()
                .reload(permission, reloadAction)
                .debug(permission, debugState, debugAction);
    }

    public CommandBuiltins help(boolean enabled, String permission) {
        helpEnabled = enabled;
        helpPermission = normalizePermission(permission, null);
        return this;
    }

    public CommandBuiltins reload(String permission, Runnable action) {
        if (action == null) {
            throw new NullPointerException("action");
        }
        reloadPermission = normalizePermission(permission, "reload");
        reloadAction = action;
        reloadAsyncAction = null;
        return this;
    }

    /** 安装重新加载操作，仅在操作完成后发送成功消息。 */
    public CommandBuiltins reloadAsync(
            String permission,
            Supplier<? extends CompletionStage<?>> action
    ) {
        if (action == null) {
            throw new NullPointerException("action");
        }
        reloadPermission = normalizePermission(permission, "reload");
        reloadAsyncAction = action;
        reloadAction = null;
        return this;
    }

    public CommandBuiltins debug(
            String permission,
            BooleanSupplier state,
            Consumer<Boolean> action
    ) {
        if (state == null) {
            throw new NullPointerException("state");
        }
        if (action == null) {
            throw new NullPointerException("action");
        }
        debugPermission = normalizePermission(permission, "debug");
        debugState = state;
        debugAction = action;
        return this;
    }

    public void install(CommandSpec spec) {
        if (spec == null) {
            throw new NullPointerException("spec");
        }
        if (helpEnabled) {
            installHelp(spec);
        }
        if (reloadAction != null || reloadAsyncAction != null) {
            spec.literal("reload", child -> {
                markDescription(child, CommandMessageKeys.BUILTIN_RELOAD_DESCRIPTION);
                applyPermission(child, reloadPermission);
                child.executes(new ReloadHandler(reloadAction, reloadAsyncAction));
            });
        }
        if (debugState != null) {
            spec.literal("debug", child -> {
                markDescription(child, CommandMessageKeys.BUILTIN_DEBUG_DESCRIPTION);
                applyPermission(child, debugPermission);
                child.executes(new DebugHandler(debugState, debugAction));
            });
        }
    }

    private void installHelp(CommandSpec spec) {
        final Arg<Integer> page = Arguments.integer("page", 1, Integer.MAX_VALUE);
        spec.literal("help", child -> {
            markDescription(child, CommandMessageKeys.BUILTIN_HELP_DESCRIPTION);
            applyPermission(child, helpPermission);
            child.executes(new HelpHandler(null));
            child.argument(page, pageNode -> pageNode.executes(new HelpHandler(page)));
        });
    }

    private static void applyPermission(CommandSpec spec, String permission) {
        if (permission != null) {
            spec.permission(permission);
        }
    }

    private static void markDescription(CommandSpec spec, String key) {
        if (!(spec instanceof CommandSpecImpl)) {
            throw new IllegalArgumentException(
                    "内建命令需要由 CommandModule / Scope.command / CommandSpecImpl.command"
                            + " 创建的命令规格");
        }
        ((CommandSpecImpl) spec).descriptionKey(key);
    }

    private static String normalizePermission(String permission, String sensitiveContext) {
        if (PERMISSION_NONE.equals(permission)) {
            return null;
        }
        if (permission == null || permission.trim().isEmpty()) {
            if (sensitiveContext != null) {
                LOGGER.warning("内置命令 " + sensitiveContext
                        + " 未设置权限，任何玩家均可执行；如确认无需权限请传"
                        + " CommandBuiltins.PERMISSION_NONE");
            }
            return null;
        }
        return permission.trim();
    }

    private static final class HelpHandler implements DispatcherAwareCommandHandler {
        private final Arg<Integer> page;

        private HelpHandler(Arg<Integer> page) {
            this.page = page;
        }

        @Override
        public void execute(CommandContext context, CommandDispatcher dispatcher) {
            int requested = page == null ? 1 : context.get(page).intValue();
            dispatcher.sendHelp(context.sender(), requested, dispatcher.defaultHelpPageSize());
        }
    }

    private static final class ReloadHandler implements DispatcherAwareCommandHandler {
        private final Runnable action;
        private final Supplier<? extends CompletionStage<?>> asyncAction;

        private ReloadHandler(
                Runnable action,
                Supplier<? extends CompletionStage<?>> asyncAction
        ) {
            this.action = action;
            this.asyncAction = asyncAction;
        }

        @Override
        public void execute(CommandContext context, CommandDispatcher dispatcher) {
            if (asyncAction == null) {
                try {
                    action.run();
                } catch (RuntimeException failure) {
                    throw new CommandFailure(
                            CommandMessageKeys.BUILTIN_RELOAD_FAILURE, failure);
                }
                dispatcher.sendMessage(context.sender(), CommandMessageKeys.BUILTIN_RELOAD_SUCCESS);
                return;
            }
            CompletionStage<?> completion = asyncAction.get();
            if (completion == null) {
                throw new IllegalStateException("reload action returned null completion stage");
            }
            completion.whenComplete((ignored, failure) -> {
                if (failure == null) {
                    dispatcher.sendMessage(
                            context.sender(), CommandMessageKeys.BUILTIN_RELOAD_SUCCESS);
                } else {
                    dispatcher.sendFailure(
                            context.sender(),
                            failure,
                            CommandMessageKeys.BUILTIN_RELOAD_FAILURE);
                }
            });
        }
    }

    private static final class DebugHandler implements DispatcherAwareCommandHandler {
        private final BooleanSupplier state;
        private final Consumer<Boolean> action;

        private DebugHandler(BooleanSupplier state, Consumer<Boolean> action) {
            this.state = state;
            this.action = action;
        }

        @Override
        public void execute(CommandContext context, CommandDispatcher dispatcher) {
            boolean enabled = !state.getAsBoolean();
            action.accept(Boolean.valueOf(enabled));
            dispatcher.sendMessage(
                    context.sender(),
                    enabled
                            ? CommandMessageKeys.BUILTIN_DEBUG_ENABLED
                            : CommandMessageKeys.BUILTIN_DEBUG_DISABLED);
        }
    }
}
