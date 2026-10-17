/* Kether action semantics: Copyright (c) 2018 Bkm016, MIT. See THIRD_PARTY_NOTICES.md. */
package me.kzheart.klib.script

import me.kzheart.klib.script.KetherSupport.Companion.action
import me.kzheart.klib.script.KetherSupport.Companion.completed
import me.kzheart.klib.script.KetherSupport.Companion.consume
import me.kzheart.klib.script.KetherSupport.Companion.elements
import me.kzheart.klib.script.KetherSupport.Companion.follow
import me.kzheart.klib.script.KetherSupport.Companion.integer
import me.kzheart.klib.script.KetherSupport.Companion.now
import me.kzheart.klib.script.KetherSupport.Companion.number
import me.kzheart.klib.script.KetherSupport.Companion.run
import me.kzheart.klib.script.KetherSupport.Companion.setRoot
import me.kzheart.klib.script.KetherSupport.Companion.text
import me.kzheart.klib.script.kether.core.ParsedAction
import me.kzheart.klib.script.kether.core.QuestActionParser as NativeParser
import me.kzheart.klib.script.kether.core.QuestContext
import me.kzheart.klib.script.kether.core.QuestReader
import java.util.Locale
import java.util.concurrent.CompletableFuture

/** 服务器动作的薄适配器，只解析动作树并调用宿主平台。 */
class GameScriptActions private constructor() {
    companion object {
        @JvmStatic fun install(registry: StatementRegistry) {
            registry.registerBuiltinKether("actionbar", textAction { frame, message -> platform(frame).actionBar(player(frame), withSender(frame, message)); null })
            val broadcast = textAction { frame, message -> platform(frame).broadcast(withSender(frame, message)); null }
            registry.registerBuiltinKether("broadcast", broadcast); registry.registerBuiltinKether("bc", broadcast)
            registry.registerBuiltinKether("players", NativeParser.of { now { frame -> ArrayList(platform(frame).onlinePlayerNames()) } })
            registry.registerBuiltinKether("switch", textAction { frame, target ->
                val platform = platform(frame)
                setRoot(frame, ScriptContext.SENDER_OVERRIDE, if (target == "console" || target == "server") platform.console() else platform.player(target)); null
            })
            registry.registerBuiltinKether("title", NativeParser.of { reader ->
                val title = reader.nextValue<Any?>()
                val subtitle = if (consume(reader, "subtitle")) reader.nextValue<Any?>() else null
                val times = times(reader)
                action { frame -> follow(frame, run(frame, title)) { main -> follow(frame, if (subtitle == null) completed(null) else run(frame, subtitle)) { sub ->
                    follow(frame, evaluate(frame, times)) { timing ->
                        val player = player(frame); val name = name(frame, player)
                        platform(frame).title(player, text(main).replace("@sender", name), if (sub == null) "§r" else text(sub).replace("@sender", name),
                            integer(timing[0]), integer(timing[1]), integer(timing[2])); completed(null)
                    }
                } } }
            })
            registry.registerBuiltinKether("subtitle", NativeParser.of { reader ->
                val subtitle = reader.nextValue<Any?>(); val times = times(reader)
                action { frame -> follow(frame, run(frame, subtitle)) { sub -> follow(frame, evaluate(frame, times)) { timing ->
                    val player = player(frame)
                    platform(frame).title(player, "§r", text(sub).replace("@sender", name(frame, player)), integer(timing[0]), integer(timing[1]), integer(timing[2])); completed(null)
                } } }
            })
            val location = NativeParser.of { reader ->
                val args = List(4) { reader.nextValue<Any?>() }
                val rotation = if (consume(reader, "and")) List(2) { reader.nextValue<Any?>() } else null
                action { frame -> follow(frame, evaluate(frame, args)) { position ->
                    follow(frame, if (rotation == null) CompletableFuture.completedFuture<List<Any?>>(listOf(0, 0)) else evaluate(frame, rotation)) { angles ->
                        completed(platform(frame).location(text(position[0]), number(position[1]), number(position[2]), number(position[3]), number(angles[0]).toFloat(), number(angles[1]).toFloat()))
                    }
                } }
            }
            registry.registerBuiltinKether("location", location); registry.registerBuiltinKether("loc", location)
            registry.registerBuiltinKether("sound", NativeParser.of { reader ->
                val sound = reader.nextValue<Any?>()
                val volume = if (consume(reader, "by", "with")) List(2) { reader.nextValue<Any?>() } else null
                action { frame -> follow(frame, run(frame, sound)) { name ->
                    follow(frame, if (volume == null) CompletableFuture.completedFuture<List<Any?>>(listOf(0, 0)) else evaluate(frame, volume)) { values ->
                        platform(frame).playSound(player(frame), soundName(text(name)), number(values[0]).toFloat(), number(values[1]).toFloat()); completed(null)
                    }
                } }
            })
            registry.registerBuiltinKether("stopsound", textAction { frame, name -> platform(frame).stopSound(player(frame), soundName(name)); null })
            registry.registerBuiltinKether("itemstack", textAction { frame, name -> platform(frame).itemStack(name) })
            registry.registerBuiltinKether("material", textAction { frame, name -> platform(frame).material(name) })
            registry.registerBuiltinKether("scoreboard", NativeParser.of { reader ->
                val content = reader.nextValue<Any?>()
                action { frame -> follow(frame, run(frame, content)) { value ->
                    val lines = when {
                        value is Collection<*> || (value != null && value.javaClass.isArray) -> elements(value).map { it.toString() }
                        value != null -> NativeCheckActions.trimIndent(value.toString()).split(Regex("\\r\\n|\\n|\\r"))
                        else -> null
                    }
                    platform(frame).scoreboard(player(frame), lines); completed(null)
                } }
            })
            val javascript = NativeParser.of { reader ->
                val source = NativeCheckActions.trimIndent(reader.nextToken())
                now { frame ->
                    val context = CoreScriptRuntime.context(frame)
                    val bindings = linkedMapOf<String, Any?>("sender" to context.sender().orElse(null), "server" to context.service(ScriptPlatform::class.java).map { it.console() }.orElse(null))
                    bindings.putAll(ScriptFrames.variables(frame))
                    context.requireService(JavaScriptEvaluator::class.java).eval(source, bindings)
                }
            }
            listOf("$", "js", "javascript").forEach { registry.registerBuiltinKether(it, javascript) }
        }
        private fun soundName(name: String): String = if (name.startsWith("resource:")) name else name.replace('.', '_').uppercase(Locale.ROOT)
        private fun times(reader: QuestReader): List<ParsedAction<*>>? = if (consume(reader, "by", "with")) List(3) { reader.nextValue<Any?>() } else null
        private fun evaluate(frame: QuestContext.Frame, actions: List<ParsedAction<*>>?): CompletableFuture<List<Any?>> =
            if (actions == null) CompletableFuture.completedFuture(listOf(0, 20, 0)) else CollectionScriptActions.collect(frame, actions)
        private fun textAction(operation: (QuestContext.Frame, String) -> Any?): NativeParser = NativeParser.of { reader ->
            val input = reader.nextValue<Any?>()
            action { frame -> follow(frame, run(frame, input)) { value -> completed(operation(frame, text(value))) } }
        }
        @JvmStatic fun platform(frame: QuestContext.Frame): ScriptPlatform = CoreScriptRuntime.context(frame).requireService(ScriptPlatform::class.java)
        @JvmStatic fun player(frame: QuestContext.Frame): Any {
            val context = CoreScriptRuntime.context(frame)
            val sender = context.sender().orElse(null)
            check(sender != null && context.requireService(ScriptSenderQuery::class.java).isPlayer(sender)) { "No player selected." }
            return sender
        }
        private fun name(frame: QuestContext.Frame, player: Any): String = CoreScriptRuntime.context(frame).requireService(ScriptSenderQuery::class.java).name(player).toString()
        private fun withSender(frame: QuestContext.Frame, message: String): String {
            if (!message.contains("@sender")) return message
            val sender = CoreScriptRuntime.context(frame).sender().orElse(null)
            return message.replace("@sender", if (sender == null) "null" else name(frame, sender))
        }
    }
}
