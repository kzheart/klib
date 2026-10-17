package me.kzheart.klib.script;

import java.util.List;
import me.kzheart.klib.script.kether.core.QuestActionParser;

/** 在脚本加载阶段为未知动作提供完整 Kether 解析器。 */
interface KetherParserResolver {

    QuestActionParser parser(String action, List<String> namespaces);
}
