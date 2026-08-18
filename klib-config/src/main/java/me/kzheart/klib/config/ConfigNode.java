package me.kzheart.klib.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

/** 一个能感知路径的 YAML 节点视图。 */
public final class ConfigNode {
    private final YamlDocument document;
    private final Node node;
    private final String path;

    ConfigNode(YamlDocument document, Node node, String path) {
        this.document = document;
        this.node = node;
        this.path = path;
    }

    public String sourceName() {
        return document.sourceName();
    }

    public String path() {
        return path;
    }

    public boolean exists() {
        return node != null;
    }

    public ConfigNode child(String key) {
        Objects.requireNonNull(key, "key");
        String childPath = path.isEmpty() ? key : path + "." + key;
        if (node == null) {
            return new ConfigNode(document, null, childPath);
        }
        if (!(node instanceof MappingNode)) {
            throw mappingError("expected a mapping before key '" + key + "'");
        }
        return new ConfigNode(
                document,
                YamlDocument.childNode((MappingNode) node, key),
                childPath);
    }

    public ConfigNode node(String relativePath) {
        Objects.requireNonNull(relativePath, "relativePath");
        ConfigNode current = this;
        for (String segment : relativePath.split("\\.")) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("Invalid configuration path: " + relativePath);
            }
            current = current.child(segment);
        }
        return current;
    }

    public Set<String> keys() {
        if (node == null) {
            return Collections.emptySet();
        }
        if (!(node instanceof MappingNode)) {
            throw mappingError("expected a mapping");
        }
        Set<String> keys = new LinkedHashSet<String>();
        for (NodeTuple tuple : ((MappingNode) node).getValue()) {
            keys.add(YamlDocument.scalarKey(tuple.getKeyNode()));
        }
        return Collections.unmodifiableSet(keys);
    }

    public Object raw() {
        try {
            return raw(node);
        } catch (ConfigException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw mappingError("invalid YAML value", failure);
        }
    }

    Node yamlNode() {
        return node;
    }

    ConfigNode indexed(Node value, int index) {
        return new ConfigNode(document, value, path + "[" + index + "]");
    }

    /** 从已解析的值节点构建子视图，避免再次查找键。 */
    ConfigNode child(String key, Node value) {
        String childPath = path.isEmpty() ? key : path + "." + key;
        return new ConfigNode(document, value, childPath);
    }

    /**
     * 构造带来源、路径和 YAML 行列信息的映射失败，供自定义 {@link ConfigConverter} 抛出。
     *
     * <p>节点存在时消息形如 {@code config.yml:12:5 (database.port): detail}。
     */
    public ConfigMappingException mappingError(String detail) {
        org.yaml.snakeyaml.error.Mark mark = ConfigLocations.startMark(node);
        return new ConfigMappingException(
                sourceName(),
                path,
                ConfigLocations.line(mark),
                ConfigLocations.column(mark),
                detail);
    }

    /** 同 {@link #mappingError(String)}，并附带底层原因。 */
    public ConfigMappingException mappingError(String detail, Throwable cause) {
        org.yaml.snakeyaml.error.Mark mark = ConfigLocations.startMark(node);
        return new ConfigMappingException(
                sourceName(),
                path,
                ConfigLocations.line(mark),
                ConfigLocations.column(mark),
                detail,
                cause);
    }

    private static Object raw(Node value) {
        if (value == null || Tag.NULL.equals(value.getTag())) {
            return null;
        }
        if (value instanceof ScalarNode) {
            ScalarNode scalar = (ScalarNode) value;
            String text = scalar.getValue();
            if (Tag.BOOL.equals(scalar.getTag())) {
                return Boolean.valueOf(text);
            }
            if (Tag.INT.equals(scalar.getTag())) {
                return parseInteger(text);
            }
            if (Tag.FLOAT.equals(scalar.getTag())) {
                return parseFloat(text);
            }
            return text;
        }
        if (value instanceof SequenceNode) {
            List<Object> result = new ArrayList<Object>();
            for (Node child : ((SequenceNode) value).getValue()) {
                result.add(raw(child));
            }
            return Collections.unmodifiableList(result);
        }
        if (value instanceof MappingNode) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (NodeTuple tuple : ((MappingNode) value).getValue()) {
                result.put(YamlDocument.scalarKey(tuple.getKeyNode()), raw(tuple.getValueNode()));
            }
            return Collections.unmodifiableMap(result);
        }
        throw new ConfigException("Unsupported YAML node: " + value.getNodeId());
    }

    private static Number parseInteger(String value) {
        String normalized = value.replace("_", "");
        int sign = 1;
        if (normalized.startsWith("-")) {
            sign = -1;
            normalized = normalized.substring(1);
        } else if (normalized.startsWith("+")) {
            normalized = normalized.substring(1);
        }
        if (normalized.indexOf(':') >= 0) {
            throw new ConfigException("YAML 1.1 sexagesimal integer '" + value
                    + "' is not supported; quote the value to keep it as a string");
        }
        int radix = 10;
        if (normalized.startsWith("0x")) {
            radix = 16;
            normalized = normalized.substring(2);
        } else if (normalized.startsWith("0o")) {
            radix = 8;
            normalized = normalized.substring(2);
        } else if (normalized.startsWith("0b")) {
            radix = 2;
            normalized = normalized.substring(2);
        } else if (normalized.length() > 1 && normalized.charAt(0) == '0') {
            throw new ConfigException("integer '" + value
                    + "' has a leading zero, which YAML 1.1 treats as octal;"
                    + " write it as 0oNNN for octal or quote the value for a string");
        }
        final long parsed;
        try {
            parsed = Long.parseLong((sign < 0 ? "-" : "") + normalized, radix);
        } catch (NumberFormatException failure) {
            throw new ConfigException("integer '" + value
                    + "' is outside the supported 64-bit range; quote the value"
                    + " to keep it as a string", failure);
        }
        if (parsed >= Integer.MIN_VALUE && parsed <= Integer.MAX_VALUE) {
            return Integer.valueOf((int) parsed);
        }
        return Long.valueOf(parsed);
    }

    private static Double parseFloat(String value) {
        String normalized = value.replace("_", "").toLowerCase(java.util.Locale.ROOT);
        if (".inf".equals(normalized) || "+.inf".equals(normalized)) {
            return Double.valueOf(Double.POSITIVE_INFINITY);
        }
        if ("-.inf".equals(normalized)) {
            return Double.valueOf(Double.NEGATIVE_INFINITY);
        }
        if (".nan".equals(normalized)) {
            return Double.valueOf(Double.NaN);
        }
        return Double.valueOf(normalized);
    }
}
