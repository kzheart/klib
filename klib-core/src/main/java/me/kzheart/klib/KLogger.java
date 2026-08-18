package me.kzheart.klib;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.kzheart.klib.scope.Disposable;

/**
 * 插件日志门面：在 Bukkit 的 {@link Logger} 之上补充统一前缀符号、按模块开关的调试日志，
 * 以及供诊断上报使用的最近日志环形缓冲。
 *
 * <p>两点行为需要注意：</p>
 * <ul>
 *   <li>{@link #debug(String, String)} 以 {@link Level#INFO} 级别发出，靠 {@link #setDebug} 的
 *       模块开关过滤，而不是靠日志级别过滤。这是绕开 Bukkit 默认隐藏 {@code FINE} 的权衡：
 *       调试输出无需玩家修改服务端日志配置即可看到。</li>
 *   <li>行首符号默认使用 {@code ❯✔⚠✖}。把系统属性 {@code klib.logger.ascii} 设为 {@code true}
 *       可改用 ASCII 前缀（{@code >}、{@code +}、{@code !}、{@code x}），用于 GBK 等无法表示这些
 *       字符的 Windows 控制台。该属性在类初始化时读取一次并静态缓存。</li>
 * </ul>
 */
public final class KLogger {
    private static final int DEFAULT_BUFFER_SIZE = 256;
    /** 默认模块名：未显式携带模块的日志在最近日志缓冲中记为 core。 */
    private static final String DEFAULT_MODULE = "core";
    private static final boolean ASCII_SYMBOLS =
            Boolean.parseBoolean(System.getProperty("klib.logger.ascii", "false"));

    private final Logger delegate;
    private final int bufferSize;
    private final Deque<String> recent = new ArrayDeque<String>();
    private final Set<String> debugModules = Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());
    private final CopyOnWriteArrayList<BiConsumer<String, Throwable>> errorObservers =
            new CopyOnWriteArrayList<BiConsumer<String, Throwable>>();
    private final ThreadLocal<Boolean> notifyingErrorObservers = new ThreadLocal<Boolean>();

    public KLogger(Logger delegate) {
        this(delegate, DEFAULT_BUFFER_SIZE);
    }

    public KLogger(Logger delegate, int bufferSize) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate cannot be null");
        }
        if (bufferSize < 1) {
            throw new IllegalArgumentException("bufferSize must be positive");
        }
        this.delegate = delegate;
        this.bufferSize = bufferSize;
    }

    public void info(String message) {
        info(null, message);
    }

    /** 记录一条 INFO 日志，并在最近日志缓冲中标注来源模块。 */
    public void info(String module, String message) {
        write(Level.INFO, Symbols.INFO, module, message, null);
    }

    public void success(String message) {
        success(null, message);
    }

    /** 记录一条成功日志（INFO 级别），并在最近日志缓冲中标注来源模块。 */
    public void success(String module, String message) {
        write(Level.INFO, Symbols.SUCCESS, module, message, null);
    }

    public void warn(String message) {
        warn(null, message);
    }

    /** 记录一条 WARNING 日志，并在最近日志缓冲中标注来源模块。 */
    public void warn(String module, String message) {
        write(Level.WARNING, Symbols.WARN, module, message, null);
    }

    public void error(String message, Throwable error) {
        error(null, message, error);
    }

    /** 记录一条 SEVERE 日志并通知错误观察者，同时在最近日志缓冲中标注来源模块。 */
    public void error(String module, String message, Throwable error) {
        write(Level.SEVERE, Symbols.ERROR, module, message, error);
        if (Boolean.TRUE.equals(notifyingErrorObservers.get())) {
            return;
        }
        notifyingErrorObservers.set(Boolean.TRUE);
        try {
            for (BiConsumer<String, Throwable> observer : errorObservers) {
                try {
                    observer.accept(message, error);
                } catch (RuntimeException observerFailure) {
                    delegate.log(Level.WARNING, "Error observer failed", observerFailure);
                }
            }
        } finally {
            notifyingErrorObservers.remove();
        }
    }

    public Disposable onError(BiConsumer<String, Throwable> observer) {
        if (observer == null) {
            throw new NullPointerException("observer");
        }
        errorObservers.add(observer);
        return new Disposable() {
            @Override
            public void dispose() {
                errorObservers.remove(observer);
            }
        };
    }

    /**
     * 记录一条模块调试日志。输出以 {@link Level#INFO} 级别发出，由 {@link #setDebug} 的模块开关
     * 决定是否发出，而不是由日志级别过滤；因此关闭开关的模块不会产生任何日志和缓冲记录。
     */
    public void debug(String module, String message) {
        String normalized = normalizeModule(module);
        if (!debugModules.contains("*") && !debugModules.contains(normalized)) {
            return;
        }
        record("DEBUG", normalized, message);
        delegate.log(Level.INFO, Symbols.INFO + " [debug:" + normalized + "] " + message);
    }

    public void setDebug(String module, boolean enabled) {
        String normalized = normalizeModule(module);
        if (enabled) {
            debugModules.add(normalized);
        } else {
            debugModules.remove(normalized);
        }
    }

    public boolean isDebugEnabled(String module) {
        String normalized = normalizeModule(module);
        return debugModules.contains("*") || debugModules.contains(normalized);
    }

    /**
     * 返回最近日志的只读快照，格式为 {@code 时间戳 级别 [模块] 消息}。
     * 其中模块取自携带模块名的重载；未携带模块名时记为 {@code core}。
     */
    public List<String> recentLines() {
        synchronized (recent) {
            return Collections.unmodifiableList(new ArrayList<String>(recent));
        }
    }

    private void write(Level level, String symbol, String module, String message, Throwable error) {
        record(level.getName(), normalizeModule(module), message);
        String rendered = symbol + " " + message;
        if (error == null) {
            delegate.log(level, rendered);
        } else {
            delegate.log(level, rendered, error);
        }
    }

    private void record(String level, String module, String message) {
        String line = Instant.now().toString() + " " + level + " [" + module + "] " + message;
        synchronized (recent) {
            while (recent.size() >= bufferSize) {
                recent.removeFirst();
            }
            recent.addLast(line);
        }
    }

    private static String normalizeModule(String module) {
        if (module == null || module.trim().isEmpty()) {
            return DEFAULT_MODULE;
        }
        return module.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** 行首符号；klib.logger.ascii=true 时降级为 ASCII，供 GBK 控制台使用。 */
    private static final class Symbols {
        static final String INFO = ASCII_SYMBOLS ? ">" : "❯";
        static final String SUCCESS = ASCII_SYMBOLS ? "+" : "✔";
        static final String WARN = ASCII_SYMBOLS ? "!" : "⚠";
        static final String ERROR = ASCII_SYMBOLS ? "x" : "✖";

        private Symbols() {
        }
    }
}
