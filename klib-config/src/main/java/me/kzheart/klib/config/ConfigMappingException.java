package me.kzheart.klib.config;

/**
 * 描述精确源路径处的类型映射失败。
 *
 * <p>当来源节点带有 YAML 位置信息时，异常同时携带 1 起始的行号与列号，
 * 消息形如 {@code config.yml:12:5 (database.port): ...}；没有位置信息时
 * 退回 {@code config.yml:database.port: ...}。
 */
public final class ConfigMappingException extends ConfigException {
    private static final long serialVersionUID = 1L;

    /** 表示位置未知。 */
    static final int UNKNOWN_POSITION = -1;

    private final String sourceName;
    private final String path;
    private final int line;
    private final int column;

    ConfigMappingException(String sourceName, String path, String detail) {
        this(sourceName, path, UNKNOWN_POSITION, UNKNOWN_POSITION, detail);
    }

    ConfigMappingException(String sourceName, String path, String detail, Throwable cause) {
        this(sourceName, path, UNKNOWN_POSITION, UNKNOWN_POSITION, detail, cause);
    }

    ConfigMappingException(String sourceName, String path, int line, int column, String detail) {
        super(format(sourceName, path, line, column, detail));
        this.sourceName = sourceName;
        this.path = path;
        this.line = line;
        this.column = column;
    }

    ConfigMappingException(
            String sourceName,
            String path,
            int line,
            int column,
            String detail,
            Throwable cause
    ) {
        super(format(sourceName, path, line, column, detail), cause);
        this.sourceName = sourceName;
        this.path = path;
        this.line = line;
        this.column = column;
    }

    public String sourceName() {
        return sourceName;
    }

    public String path() {
        return path;
    }

    /** 出错节点在源文件中的行号（从 1 开始）；未知时返回 {@code -1}。 */
    public int line() {
        return line;
    }

    /** 出错节点在源文件中的列号（从 1 开始）；未知时返回 {@code -1}。 */
    public int column() {
        return column;
    }

    /** 是否携带可用的行列位置。 */
    public boolean hasLocation() {
        return line > 0;
    }

    private static String format(String sourceName, String path, int line, int column, String detail) {
        return ConfigLocations.prefix(sourceName, path, line, column) + ": " + detail;
    }
}
