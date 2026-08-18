package me.kzheart.klib.script;

/** 带源码位置和稳定错误码的本地化失败。 */
public final class ScriptException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final int line;
    private final int column;

    ScriptException(String code, String message, int line, int column, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.line = line;
        this.column = column;
    }

    public String code() {
        return code;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }
}
