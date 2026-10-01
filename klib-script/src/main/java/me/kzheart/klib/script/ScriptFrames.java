package me.kzheart.klib.script;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import me.kzheart.klib.script.kether.core.QuestContext;

/** 原生 Kether 动作访问当前宿主服务与变量的公共入口。 */
public final class ScriptFrames {
    private ScriptFrames() { }

    /**
     * 返回绑定到当前帧的视图。变量读取沿原生父链查找，写入沿 VarTable.set 的规则；
     * 非局部变量写至根，~ 开头的局部变量留在当前帧。内部 ~klib: 键不可见/不可写。
     * 视图的使用线程和生命周期与原 Frame 相同，不是可跨线程独立使用的快照。
     */
    public static ScriptContext context(QuestContext.Frame frame) {
        return CoreScriptRuntime.context(Objects.requireNonNull(frame, "frame"));
    }

    /**
     * 父层在前、近层覆盖的只读原始变量快照，保留 null 与 QuestFuture，排除内部键。
     * 需要延迟变量的实际值时使用 context(frame).variable，而非将快照中的值强制转换。
     */
    public static Map<String, Object> variables(QuestContext.Frame frame) {
        Objects.requireNonNull(frame, "frame");
        List<QuestContext.VarTable> tables = new ArrayList<QuestContext.VarTable>();
        for (QuestContext.VarTable table = frame.variables(); table != null; table = table.parent()) {
            tables.add(table);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = tables.size() - 1; index >= 0; index--) {
            for (Map.Entry<String, Object> entry : tables.get(index).values()) {
                if (!isInternal(entry.getKey())) result.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /** 在同一宿主服务、变量视图与嵌套深度预算下执行脚本，完成后写回变量差量。 */
    public static CompletionStage<Object> eval(QuestContext.Frame frame, String source) {
        return CoreScriptRuntime.evalNested(Objects.requireNonNull(frame, "frame"),
                Objects.requireNonNull(source, "source"));
    }

    static boolean isInternal(String name) { return name.startsWith("~klib:"); }

    static void checkWritable(String name) {
        if (isInternal(name)) throw new IllegalArgumentException("Reserved script variable: " + name);
    }
}
