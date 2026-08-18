package me.kzheart.klib.config;

import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.nodes.Node;

/** 统一拼接“来源 + 行列 + 路径”前缀，并把 SnakeYAML 的 0 起始 Mark 换算为 1 起始行列。 */
final class ConfigLocations {
    private ConfigLocations() {
    }

    /** SnakeYAML 的 {@link Mark} 行列均从 0 开始，这里换算为编辑器一致的 1 起始行号。 */
    static int line(Mark mark) {
        return mark == null ? ConfigMappingException.UNKNOWN_POSITION : mark.getLine() + 1;
    }

    static int column(Mark mark) {
        return mark == null ? ConfigMappingException.UNKNOWN_POSITION : mark.getColumn() + 1;
    }

    static Mark startMark(Node node) {
        return node == null ? null : node.getStartMark();
    }

    /**
     * 拼出 {@code config.yml:12:5 (database.port)} 或 {@code config.yml:database.port}。
     * 空路径显示为 {@code <root>}。
     */
    static String prefix(String sourceName, String path, int line, int column) {
        String displayPath = path == null || path.isEmpty() ? "<root>" : path;
        if (line <= 0) {
            return sourceName + ":" + displayPath;
        }
        StringBuilder result = new StringBuilder(sourceName).append(':').append(line);
        if (column > 0) {
            result.append(':').append(column);
        }
        return result.append(" (").append(displayPath).append(')').toString();
    }

    static String prefix(String sourceName, String path, Mark mark) {
        return prefix(sourceName, path, line(mark), column(mark));
    }
}
