package me.kzheart.klib.config;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.comments.CommentLine;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.nodes.AnchorNode;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

/** 可变且能感知注释的 YAML 文档。 */
public final class YamlDocument {
    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(YamlDocument.class.getName());

    private final String sourceName;
    private final Yaml yaml;
    private final MappingNode root;

    private YamlDocument(String sourceName, Yaml yaml, MappingNode root) {
        this.sourceName = sourceName;
        this.yaml = yaml;
        this.root = root;
    }

    public static YamlDocument parse(String sourceName, String content) {
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(content, "content");

        LoaderOptions loader = new LoaderOptions();
        loader.setAllowDuplicateKeys(false);
        loader.setProcessComments(true);
        DumperOptions dumper = new DumperOptions();
        dumper.setProcessComments(true);
        dumper.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dumper.setPrettyFlow(true);
        dumper.setIndent(2);
        dumper.setIndicatorIndent(2);
        dumper.setIndentWithIndicator(true);
        dumper.setSplitLines(false);
        Yaml yaml = new Yaml(
                new SafeConstructor(loader),
                new Representer(dumper),
                dumper,
                loader);

        final Node composed;
        try {
            composed = yaml.compose(new StringReader(content));
        } catch (MarkedYAMLException failure) {
            Mark mark = failure.getProblemMark() != null
                    ? failure.getProblemMark()
                    : failure.getContextMark();
            throw new ConfigException(
                    ConfigLocations.prefix(sourceName, "", mark) + ": invalid YAML", failure);
        } catch (RuntimeException failure) {
            throw new ConfigException(sourceName + ":<root>: invalid YAML", failure);
        }
        if (composed == null) {
            return new YamlDocument(sourceName, yaml, emptyMapping());
        }
        if (!(composed instanceof MappingNode)) {
            throw new ConfigException(sourceName + ":<root>: the YAML root must be a mapping");
        }
        validateAcyclic(sourceName, composed);
        return new YamlDocument(sourceName, yaml, (MappingNode) composed);
    }

    private static void validateAcyclic(String sourceName, Node root) {
        visitNode(
                sourceName,
                root,
                new IdentityHashMap<Node, Boolean>(),
                new IdentityHashMap<Node, Boolean>());
    }

    private static void visitNode(
            String sourceName,
            Node candidate,
            Map<Node, Boolean> active,
            Map<Node, Boolean> complete
    ) {
        Node node = candidate instanceof AnchorNode
                ? ((AnchorNode) candidate).getRealNode()
                : candidate;
        if (complete.containsKey(node)) {
            return;
        }
        if (active.put(node, Boolean.TRUE) != null) {
            throw new ConfigException(sourceName + ":<root>: recursive YAML aliases are not supported");
        }
        if (node instanceof SequenceNode) {
            for (Node child : ((SequenceNode) node).getValue()) {
                visitNode(sourceName, child, active, complete);
            }
        } else if (node instanceof MappingNode) {
            for (NodeTuple tuple : ((MappingNode) node).getValue()) {
                visitNode(sourceName, tuple.getKeyNode(), active, complete);
                visitNode(sourceName, tuple.getValueNode(), active, complete);
            }
        }
        active.remove(node);
        complete.put(node, Boolean.TRUE);
    }

    public String sourceName() {
        return sourceName;
    }

    public ConfigNode root() {
        return new ConfigNode(this, root, "");
    }

    public ConfigNode node(String path) {
        Objects.requireNonNull(path, "path");
        ConfigNode current = root();
        if (path.isEmpty()) {
            return current;
        }
        String[] segments = splitPath(path);
        for (String segment : segments) {
            current = current.child(segment);
        }
        return current;
    }

    /** 仅从默认配置中补充缺失值，并保留现有节点和注释。 */
    public YamlDocument mergeDefaults(YamlDocument defaults) {
        Objects.requireNonNull(defaults, "defaults");
        mergeMappings(root, defaults.root);
        return this;
    }

    boolean mergeDefaultsChanged(YamlDocument defaults) {
        Objects.requireNonNull(defaults, "defaults");
        return mergeMappings(root, defaults.root);
    }

    public int schemaVersion() {
        ConfigNode version = node("_schema-version");
        if (!version.exists()) {
            return 0;
        }
        Object value = version.raw();
        if (!(value instanceof Number)) {
            throw version.mappingError("schema version must be an integer");
        }
        long parsed = ((Number) value).longValue();
        if (parsed < 0L || parsed > Integer.MAX_VALUE) {
            throw version.mappingError("schema version is outside integer range");
        }
        return (int) parsed;
    }

    public String toYaml() {
        StringWriter output = new StringWriter();
        yaml.serialize(root, output);
        return output.toString();
    }

    MappingNode rootNode() {
        return root;
    }

    /** 深拷贝已解析模板，复用时不共享可变节点。 */
    YamlDocument copy(String copySourceName) {
        return new YamlDocument(copySourceName, yaml, (MappingNode) copyNode(root));
    }

    Node findNode(String path) {
        return node(path).yamlNode();
    }

    void rename(String sourcePath, String targetPath) {
        String[] source = splitPath(sourcePath);
        String[] target = splitPath(targetPath);
        if (sourcePath.equals(targetPath)) {
            return;
        }

        MappingNode sourceParent = findMappingParent(source, false);
        if (sourceParent == null) {
            return;
        }
        int sourceIndex = tupleIndex(sourceParent, source[source.length - 1]);
        if (sourceIndex < 0) {
            return;
        }
        NodeTuple sourceTuple = sourceParent.getValue().get(sourceIndex);

        MappingNode targetParent = findMappingParent(target, true);
        String targetKey = target[target.length - 1];
        int existingIndex = tupleIndex(targetParent, targetKey);
        if (existingIndex >= 0) {
            Node existingValue = targetParent.getValue().get(existingIndex).getValueNode();
            if (!nodeEquals(sourceTuple.getValueNode(), existingValue)) {
                LOGGER.warning(sourceName + ": rename '" + sourcePath + "' -> '" + targetPath
                        + "' dropped the source value because the target key already exists"
                        + " with a different value");
            }
            sourceParent.getValue().remove(sourceIndex);
            return;
        }

        ScalarNode renamedKey = scalarKey(targetKey);
        copyComments(sourceTuple.getKeyNode(), renamedKey);
        NodeTuple renamed = new NodeTuple(renamedKey, sourceTuple.getValueNode());
        sourceParent.getValue().remove(sourceIndex);
        targetParent.getValue().add(renamed);
    }

    void setInteger(String path, int value) {
        String[] segments = splitPath(path);
        MappingNode parent = findMappingParent(segments, true);
        String key = segments[segments.length - 1];
        NodeTuple replacement = new NodeTuple(
                scalarKey(key),
                new ScalarNode(
                        Tag.INT,
                        Integer.toString(value),
                        null,
                        null,
                        DumperOptions.ScalarStyle.PLAIN));
        int index = tupleIndex(parent, key);
        if (index < 0) {
            parent.getValue().add(replacement);
        } else {
            Node oldKey = parent.getValue().get(index).getKeyNode();
            copyComments(oldKey, replacement.getKeyNode());
            parent.getValue().set(index, replacement);
        }
    }

    static Node childNode(MappingNode mapping, String key) {
        int index = tupleIndex(mapping, key);
        return index < 0 ? null : mapping.getValue().get(index).getValueNode();
    }

    static String scalarKey(Node keyNode) {
        if (!(keyNode instanceof ScalarNode)) {
            throw new ConfigException("Only scalar YAML keys are supported");
        }
        return ((ScalarNode) keyNode).getValue();
    }

    private MappingNode findMappingParent(String[] path, boolean create) {
        MappingNode current = root;
        for (int index = 0; index < path.length - 1; index++) {
            String segment = path[index];
            Node child = childNode(current, segment);
            if (child == null) {
                if (!create) {
                    return null;
                }
                MappingNode created = emptyMapping();
                current.getValue().add(new NodeTuple(scalarKey(segment), created));
                current = created;
            } else if (child instanceof MappingNode) {
                current = (MappingNode) child;
            } else {
                throw new ConfigException(
                        sourceName + ":" + join(path, index + 1)
                                + ": expected a mapping while applying migration");
            }
        }
        return current;
    }

    private static boolean mergeMappings(MappingNode target, MappingNode defaults) {
        boolean changed = false;
        for (NodeTuple defaultTuple : defaults.getValue()) {
            String key = scalarKey(defaultTuple.getKeyNode());
            int targetIndex = tupleIndex(target, key);
            if (targetIndex < 0) {
                target.getValue().add(copyTuple(defaultTuple));
                changed = true;
                continue;
            }
            Node targetValue = target.getValue().get(targetIndex).getValueNode();
            Node defaultValue = defaultTuple.getValueNode();
            if (targetValue instanceof MappingNode && defaultValue instanceof MappingNode) {
                changed |= mergeMappings((MappingNode) targetValue, (MappingNode) defaultValue);
            }
        }
        return changed;
    }

    private static NodeTuple copyTuple(NodeTuple tuple) {
        return new NodeTuple(copyNode(tuple.getKeyNode()), copyNode(tuple.getValueNode()));
    }

    private static Node copyNode(Node node) {
        final Node copy;
        if (node instanceof ScalarNode) {
            ScalarNode scalar = (ScalarNode) node;
            copy = new ScalarNode(
                    scalar.getTag(),
                    scalar.getValue(),
                    null,
                    null,
                    scalar.getScalarStyle());
        } else if (node instanceof SequenceNode) {
            SequenceNode sequence = (SequenceNode) node;
            List<Node> values = new ArrayList<Node>();
            for (Node value : sequence.getValue()) {
                values.add(copyNode(value));
            }
            copy = new SequenceNode(sequence.getTag(), values, sequence.getFlowStyle());
        } else if (node instanceof MappingNode) {
            MappingNode mapping = (MappingNode) node;
            List<NodeTuple> values = new ArrayList<NodeTuple>();
            for (NodeTuple tuple : mapping.getValue()) {
                values.add(copyTuple(tuple));
            }
            copy = new MappingNode(mapping.getTag(), values, mapping.getFlowStyle());
        } else if (node instanceof AnchorNode) {
            copy = new AnchorNode(copyNode(((AnchorNode) node).getRealNode()));
        } else {
            throw new ConfigException("Unsupported YAML node: " + node.getNodeId());
        }
        copyComments(node, copy);
        return copy;
    }

    private static void copyComments(Node source, Node target) {
        target.setBlockComments(copyComments(source.getBlockComments()));
        target.setInLineComments(copyComments(source.getInLineComments()));
        target.setEndComments(copyComments(source.getEndComments()));
    }

    private static List<CommentLine> copyComments(List<CommentLine> comments) {
        return comments == null ? null : new ArrayList<CommentLine>(comments);
    }

    private static boolean nodeEquals(Node left, Node right) {
        Node first = left instanceof AnchorNode ? ((AnchorNode) left).getRealNode() : left;
        Node second = right instanceof AnchorNode ? ((AnchorNode) right).getRealNode() : right;
        if (first instanceof ScalarNode && second instanceof ScalarNode) {
            return ((ScalarNode) first).getTag().equals(((ScalarNode) second).getTag())
                    && ((ScalarNode) first).getValue().equals(((ScalarNode) second).getValue());
        }
        if (first instanceof SequenceNode && second instanceof SequenceNode) {
            List<Node> leftValues = ((SequenceNode) first).getValue();
            List<Node> rightValues = ((SequenceNode) second).getValue();
            if (leftValues.size() != rightValues.size()) {
                return false;
            }
            for (int index = 0; index < leftValues.size(); index++) {
                if (!nodeEquals(leftValues.get(index), rightValues.get(index))) {
                    return false;
                }
            }
            return true;
        }
        if (first instanceof MappingNode && second instanceof MappingNode) {
            List<NodeTuple> leftTuples = ((MappingNode) first).getValue();
            List<NodeTuple> rightTuples = ((MappingNode) second).getValue();
            if (leftTuples.size() != rightTuples.size()) {
                return false;
            }
            for (NodeTuple leftTuple : leftTuples) {
                Node match = childNode((MappingNode) second, scalarKey(leftTuple.getKeyNode()));
                if (match == null || !nodeEquals(leftTuple.getValueNode(), match)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static int tupleIndex(MappingNode mapping, String key) {
        List<NodeTuple> tuples = mapping.getValue();
        for (int index = 0; index < tuples.size(); index++) {
            if (key.equals(scalarKey(tuples.get(index).getKeyNode()))) {
                return index;
            }
        }
        return -1;
    }

    private static MappingNode emptyMapping() {
        return new MappingNode(
                Tag.MAP,
                new ArrayList<NodeTuple>(),
                DumperOptions.FlowStyle.BLOCK);
    }

    private static ScalarNode scalarKey(String key) {
        return new ScalarNode(Tag.STR, key, null, null, DumperOptions.ScalarStyle.PLAIN);
    }

    private static String[] splitPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("Configuration path must not be blank");
        }
        String[] segments = path.split("\\.");
        for (String segment : segments) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("Invalid configuration path: " + path);
            }
        }
        return segments;
    }

    private static String join(String[] segments, int length) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < length; index++) {
            if (index > 0) {
                result.append('.');
            }
            result.append(segments[index]);
        }
        return result.toString();
    }
}
