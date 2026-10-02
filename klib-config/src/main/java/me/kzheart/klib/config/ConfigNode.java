package me.kzheart.klib.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

/** 一个能感知路径的 YAML 或已解析原值节点视图。 */
public final class ConfigNode {
    private final YamlDocument document;
    private final Node node;
    private final String path;
    private final String name;
    private final ValueDocument valueDocument;
    private final Object value;
    private final boolean valueExists;
    private final Object valueParent;
    private final Object valueKey;
    private org.yaml.snakeyaml.error.Mark keyMark;

    ConfigNode(YamlDocument document, Node node, String path) {
        this(document, node, path, path.substring(path.lastIndexOf('.') + 1));
    }

    private ConfigNode(YamlDocument document, Node node, String path, String name) {
        this.document = document;
        this.node = node;
        this.path = path;
        this.name = name;
        this.valueDocument = null;
        this.value = null;
        this.valueExists = false;
        this.valueParent = null;
        this.valueKey = null;
    }

    ConfigNode(ValueDocument document, Object value, boolean exists, String path, String name) {
        this(document, value, exists, path, name, null, null);
    }

    private ConfigNode(ValueDocument document, Object value, boolean exists, String path, String name,
            Object parent, Object key) {
        this.document = null;
        this.node = null;
        this.path = path;
        this.name = name;
        this.valueDocument = document;
        this.value = value;
        this.valueExists = exists;
        this.valueParent = parent;
        this.valueKey = key;
    }

    public String sourceName() {
        return valueDocument == null ? document.sourceName() : valueDocument.sourceName();
    }

    public String path() {
        return path;
    }

    /** 原样单个键；根为空串，列表元素为 [index]。 */
    public String name() { return name; }

    /** 用文档的 writer 渲染当前映射子节；不会隐式把标量或列表转为映射。 */
    public String sectionText() {
        if (!isMapping()) throw mappingError("expected a mapping to render a section");
        return valueDocument == null ? document.sectionText((MappingNode) node)
                : valueDocument.sectionText(this, value);
    }

    /**
     * 同文档节点身份比较，不比较内容，也不暴露底层可变对象。
     * YAML 比较 AST 节点；原值 mapping/list 比较容器引用，标量（含显式 null）
     * 比较父容器身份、原样键/索引及值引用，避免缓存标量或同文本路径混同。
     * 缺失节点或 null 参数始终返回 false。
     */
    public boolean sameNode(ConfigNode other) {
        if (other == null || !exists() || !other.exists()) return false;
        if (valueDocument == null) return other.valueDocument == null && document == other.document && node == other.node;
        if (valueDocument != other.valueDocument) return false;
        if (value instanceof Map<?, ?> || value instanceof List<?>) return value == other.value;
        return valueParent == other.valueParent && Objects.equals(valueKey, other.valueKey) && value == other.value;
    }

    public boolean exists() {
        return valueDocument == null ? node != null : valueExists;
    }

    public ConfigNode child(String key) {
        Objects.requireNonNull(key, "key");
        String childPath = path.isEmpty() ? key : path + "." + key;
        if (valueDocument != null) {
            if (!valueExists) return new ConfigNode(valueDocument, null, false, childPath, key);
            if (!(value instanceof Map<?, ?>)) throw mappingError("expected a mapping before key '" + key + "'");
            Map<String, Object> mapping = ValueDocument.map(value);
            return new ConfigNode(valueDocument, mapping.get(key), mapping.containsKey(key), childPath, key, value, key);
        }
        if (node == null) {
            return new ConfigNode(document, null, childPath, key);
        }
        if (!(node instanceof MappingNode)) {
            throw mappingError("expected a mapping before key '" + key + "'");
        }
        return new ConfigNode(
                document,
                YamlDocument.childNode((MappingNode) node, key),
                childPath, key);
    }

    /**
     * 在当前映射内创建空子节，替换同名值。key 与 child 一样是单个原样键，点号不拆分。
     * 当前节点必须是已存在的映射；不会隐式创建缺失祖先。已有子视图仍引用原节点。
     */
    public ConfigNode createSection(String key) {
        Objects.requireNonNull(key, "key");
        if (valueDocument != null) {
            if (!isMapping()) throw mappingError("expected an existing mapping before key '" + key + "'");
            Map<String, Object> created = new LinkedHashMap<String, Object>();
            ValueDocument.map(value).put(key, created);
            return child(key);
        }
        if (!(node instanceof MappingNode)) {
            throw mappingError("expected an existing mapping before key '" + key + "'");
        }
        MappingNode created = new MappingNode(Tag.MAP, new ArrayList<NodeTuple>(),
                DumperOptions.FlowStyle.BLOCK);
        List<NodeTuple> entries = ((MappingNode) node).getValue();
        for (int index = 0; index < entries.size(); index++) {
            NodeTuple entry = entries.get(index);
            if (YamlDocument.scalarKey(entry.getKeyNode()).equals(key)) {
                entries.set(index, new NodeTuple(entry.getKeyNode(), created));
                return child(key, created);
            }
        }
        entries.add(new NodeTuple(new ScalarNode(Tag.STR, key, null, null,
                DumperOptions.ScalarStyle.PLAIN), created));
        return child(key, created);
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
        if (valueDocument != null) {
            if (!valueExists) return Collections.emptySet();
            if (!isMapping()) throw mappingError("expected a mapping");
            return Collections.unmodifiableSet(new LinkedHashSet<String>(ValueDocument.map(value).keySet()));
        }
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
        if (valueDocument != null) return ValueDocument.snapshot(value);
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
        return new ConfigNode(document, value, path + "[" + index + "]", "[" + index + "]");
    }

    /** 读取列表的原节点视图；越界时抛出 IndexOutOfBoundsException。 */
    public ConfigNode index(int index) {
        if (!isSequence()) throw mappingError("expected a sequence before index '" + index + "'");
        if (valueDocument == null) return indexed(((SequenceNode) node).getValue().get(index), index);
        return new ConfigNode(valueDocument, ((List<?>) value).get(index), true, path + "[" + index + "]", "[" + index + "]", value, index);
    }

    boolean isMapping() { return valueDocument == null ? node instanceof MappingNode : valueExists && value instanceof Map<?, ?>; }
    boolean isSequence() { return valueDocument == null ? node instanceof SequenceNode : valueExists && value instanceof List<?>; }
    int sequenceSize() { return valueDocument == null ? ((SequenceNode) node).getValue().size() : ((List<?>) value).size(); }

    List<ConfigNode> mappingChildren() {
        List<ConfigNode> children = new ArrayList<ConfigNode>();
        if (valueDocument != null) {
            for (String key : keys()) children.add(child(key));
        } else {
            for (NodeTuple tuple : ((MappingNode) node).getValue()) {
                ConfigNode child = child(YamlDocument.scalarKey(tuple.getKeyNode()), tuple.getValueNode());
                child.keyMark = ConfigLocations.startMark(tuple.getKeyNode());
                children.add(child);
            }
        }
        return children;
    }
    String keyLocationPrefix() { return ConfigLocations.prefix(sourceName(), path, keyMark); }

    /** 从已解析的值节点构建子视图，避免再次查找键。 */
    ConfigNode child(String key, Node value) {
        String childPath = path.isEmpty() ? key : path + "." + key;
        return new ConfigNode(document, value, childPath, key);
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
