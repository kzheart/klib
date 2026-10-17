/* Kether action semantics: Copyright (c) 2018 Bkm016, MIT. See THIRD_PARTY_NOTICES.md. */
package me.kzheart.klib.script

import me.kzheart.klib.script.KetherSupport.Companion.action
import me.kzheart.klib.script.KetherSupport.Companion.completed
import me.kzheart.klib.script.KetherSupport.Companion.consume
import me.kzheart.klib.script.KetherSupport.Companion.expects
import me.kzheart.klib.script.KetherSupport.Companion.follow
import me.kzheart.klib.script.KetherSupport.Companion.longValue
import me.kzheart.klib.script.KetherSupport.Companion.now
import me.kzheart.klib.script.KetherSupport.Companion.number
import me.kzheart.klib.script.KetherSupport.Companion.run
import me.kzheart.klib.script.KetherSupport.Companion.text
import me.kzheart.klib.script.kether.core.QuestActionParser as NativeParser
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.util.Date
import java.util.regex.Pattern

class TextScriptActions private constructor() {
    companion object {
        private val COLOR_CODE = Pattern.compile("(?i)§[0-9A-FK-ORX]")
        @JvmStatic fun install(registry: StatementRegistry) {
            register(registry, unary { uncolored(text(it)) }, "uncolor", "uncolored")
            register(registry, unary { BigDecimal.valueOf(number(it)).setScale(2, RoundingMode.HALF_UP).toDouble() }, "scale", "scaled")
            registry.registerBuiltinKether("format", withOption { value, pattern -> SimpleDateFormat(pattern ?: "yyyy/MM/dd HH:mm").format(Date(longValue(value))) })
            registry.registerBuiltinKether("printed", withOption { value, separator -> printed(text(value), separator ?: "_") })
            registry.registerBuiltinKether("match", NativeParser.of { reader ->
                val source = reader.nextValue<Any?>(); expects(reader, "by", "with", "using")
                val pattern = reader.nextValue<Any?>()
                action { frame -> follow(frame, run(frame, source)) { input -> follow(frame, run(frame, pattern)) { regex ->
                    completed(Pattern.compile(text(regex), Pattern.CASE_INSENSITIVE).matcher(text(input)).apply { find() })
                } } }
            })
            val time = NativeParser.of { reader ->
                val format = if (consume(reader, "as")) reader.nextToken() else null
                now { if (format == null) System.currentTimeMillis() else SimpleDateFormat(format).format(Date()) }
            }
            register(registry, time, "time", "date")
            val day = NativeParser.of { reader ->
                expects(reader, "of", "in")
                val unit = expects(reader, "year", "month", "week")
                now { LocalDateTime.now().let { current -> when (unit) { "year" -> current.dayOfYear; "month" -> current.dayOfMonth; else -> current.dayOfWeek.value }.toLong() } }
            }
            register(registry, day, "day", "days")
            clock(registry, { LocalDateTime.now().year.toLong() }, "year", "years")
            clock(registry, { LocalDateTime.now().monthValue.toLong() }, "month", "months")
            clock(registry, { LocalDateTime.now().hour.toLong() }, "hour", "hours")
            clock(registry, { LocalDateTime.now().minute.toLong() }, "minute", "minutes")
            clock(registry, { LocalDateTime.now().second.toLong() }, "second", "seconds")
        }
        @JvmStatic fun uncolored(text: String): String = COLOR_CODE.matcher(StructuredScriptActions.color(text)).replaceAll("")
        @JvmStatic fun printed(text: String, separator: String): List<Any?> {
            val result = ArrayList<Any?>()
            var index = 0
            while (index < text.length) {
                if (text[index] == '§') index++ else result.add(text.substring(0, index + 1) + if (index % 2 == 1) separator else "")
                index++
            }
            if (separator.isNotEmpty() && index % 2 == 0) result.add(text)
            return result
        }
        private fun register(registry: StatementRegistry, parser: NativeParser, vararg names: String) { names.forEach { registry.registerBuiltinKether(it, parser) } }
        private fun clock(registry: StatementRegistry, value: () -> Any?, vararg names: String) = register(registry, NativeParser.of { now { value() } }, *names)
        private fun unary(operation: (Any?) -> Any?): NativeParser = NativeParser.of { reader ->
            val input = reader.nextValue<Any?>()
            action { frame -> follow(frame, run(frame, input)) { value -> completed(operation(value)) } }
        }
        private fun withOption(operation: (Any?, String?) -> Any?): NativeParser = NativeParser.of { reader ->
            val input = reader.nextValue<Any?>()
            val option = if (consume(reader, "by", "with")) reader.nextValue<Any?>() else null
            action { frame -> follow(frame, run(frame, input)) { value ->
                if (option == null) completed(operation(value, null)) else follow(frame, run(frame, option)) { extra -> completed(operation(value, text(extra))) }
            } }
        }
    }
}
