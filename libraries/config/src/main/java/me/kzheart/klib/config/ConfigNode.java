package me.kzheart.klib.config;

import java.util.List;
import java.util.Set;
import org.yaml.snakeyaml.nodes.Node;

/** 路径感知节点的 Java 平台返回契约；节点状态与解析全部由 Kotlin 实现。
 * 旧 Kotlin 调用 if (node.raw() != null) node.raw().javaClass 依赖平台类型；缺失和显式空节点仍返回 null。
 */
public final class ConfigNode {
    private final ConfigNodeValue state;
    ConfigNode(ConfigNodeValue state) { this.state = state; }
    ConfigNode(YamlDocument document, Node node, String path) { this(new ConfigNodeValue(document, node, path)); }
    ConfigNode(ValueDocument document, Object value, boolean exists, String path, String name) {
        this(new ConfigNodeValue(document, value, exists, path, name));
    }
    public String sourceName() { return state.sourceName(); }
    public String path() { return state.path(); }
    public String name() { return state.name(); }
    public String sectionText() { return state.sectionText(this); }
    public boolean sameNode(ConfigNode other) { return state.sameNode(other == null ? null : other.state); }
    public boolean exists() { return state.exists(); }
    public ConfigNode child(String key) { return view(state.child(key)); }
    public ConfigNode createSection(String key) { return view(state.createSection(key)); }
    public ConfigNode node(String relativePath) { return view(state.node(relativePath)); }
    public Set<String> keys() { return state.keys(); }
    public Object raw() { return state.raw(); }
    Node yamlNode() { return state.yamlNode(); }
    ConfigNode indexed(Node value, int index) { return view(state.indexed(value, index)); }
    public ConfigNode index(int index) { return view(state.index(index)); }
    boolean isMapping() { return state.isMapping(); }
    boolean isSequence() { return state.isSequence(); }
    int sequenceSize() { return state.sequenceSize(); }
    List<ConfigNode> mappingChildren() { return state.mappingViews(); }
    String keyLocationPrefix() { return state.keyLocationPrefix(); }
    ConfigNode child(String key, Node value) { return view(state.child(key, value)); }
    public ConfigMappingException mappingError(String detail) { return state.mappingError(detail); }
    public ConfigMappingException mappingError(String detail, Throwable cause) { return state.mappingError(detail, cause); }
    ConfigMappingException keyMappingError(String detail) { return state.keyMappingError(detail); }
    private ConfigNode view(ConfigNodeValue value) { return value == state ? this : new ConfigNode(value); }
}
