package me.kzheart.klib.script

import me.kzheart.klib.script.kether.core.ParsedAction
import me.kzheart.klib.script.kether.core.QuestAction
import me.kzheart.klib.script.kether.core.QuestActionParser as NativeParser
import me.kzheart.klib.script.kether.core.QuestReader

/** 保留完整动作树，按序求值全部布尔项。 */
class NativeLogicalActions private constructor() {
    companion object {
        @JvmStatic fun install(registry: StatementRegistry) {
            registry.registerBuiltinKether("all", NativeParser.of { group(it, true) })
            registry.registerBuiltinKether("any", NativeParser.of { group(it, false) })
        }
        private fun group(reader: QuestReader, all: Boolean): QuestAction<Any?> {
            reader.expect("[")
            val values = ArrayList<ParsedAction<*>>()
            while (reader.hasNext() && reader.peek() != ']') values.add(reader.nextValue<Any?>())
            reader.expect("]")
            return KetherSupport.action { frame ->
                values.fold(KetherSupport.completed(all)) { result, value ->
                    KetherSupport.follow(frame, result) { previous ->
                        KetherSupport.follow(frame, KetherSupport.run(frame, value)) { current ->
                            val truth = InlineValues.truthy(current)
                            KetherSupport.completed(if (all) previous as Boolean && truth else previous as Boolean || truth)
                        }
                    }
                }
            }
        }
    }
}
