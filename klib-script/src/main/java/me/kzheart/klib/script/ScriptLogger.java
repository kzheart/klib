package me.kzheart.klib.script;

import java.util.logging.Level;

/** 宿主日志输出，供 {@code log}、{@code warn}、{@code error} 等语句使用。 */
@FunctionalInterface
public interface ScriptLogger {

    void log(Level level, String message);
}
