package me.kzheart.klib.command

import java.math.BigDecimal
import java.util.Locale
import me.kzheart.klib.command.api.CommandContext
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** Argument factories share parsing and suggestion rules without exposing mutable parser state. */
class Arguments private constructor() {
    companion object {
        private val booleanValues = listOf("true", "false", "yes", "no", "on", "off", "1", "0")

        @JvmStatic fun string(name: String): Arg<String> = string(name, null)
        @JvmStatic fun string(name: String, suggestions: SuggestionProvider?): Arg<String> =
            object : Arg<String>(name, false) {
                override fun parse(input: String, players: PlayerResolver): String = input
                override fun isCatchAll(): Boolean = true
                override fun suggest(sender: CommandSender, prefix: String, players: PlayerResolver): List<String> =
                    matching(suggestions?.suggest(sender, prefix), prefix)
            }

        @JvmStatic fun <T> custom(name: String, parser: ArgumentParser<T?>, suggester: SuggestionProvider?): Arg<T> =
            object : Arg<T>(name, false) {
                @Throws(ArgumentException::class)
                override fun parse(input: String, players: PlayerResolver): T = customValue(input) { parser.parse(input) }
                override fun suggest(sender: CommandSender, prefix: String, players: PlayerResolver): List<String> =
                    matching(suggester?.suggest(sender, prefix), prefix)
            }

        /** Retains Kotlin calls with predeclared Java SAMs while the three-argument JVM API stays invariant. */
        @JvmSynthetic
        fun <T> custom(name: String, parser: ArgumentParser<T>, suggester: SuggestionProvider?,
            @Suppress("UNUSED_PARAMETER") typed: Unit = Unit): Arg<T> =
            custom(name, ArgumentParser<T?> { parser.parse(it) }, suggester)

        @JvmStatic fun integer(name: String): Arg<Int> = integer(name, Int.MIN_VALUE, Int.MAX_VALUE)
        @JvmStatic fun integer(name: String, minimum: Int, maximum: Int): Arg<Int> {
            require(minimum <= maximum) { "minimum must not exceed maximum" }
            return object : Arg<Int>(name, false) {
                @Throws(ArgumentException::class)
                override fun parse(input: String, players: PlayerResolver): Int {
                    val value = input.toIntOrNull() ?: throw ArgumentException(CommandMessageKeys.ARG_INTEGER)
                    if (value < minimum || value > maximum) throw ArgumentException(
                        CommandMessageKeys.ARG_INTEGER_RANGE, "minimum", minimum, "maximum", maximum,
                        "range", range(minimum.takeUnless { it == Int.MIN_VALUE }?.toString(),
                            maximum.takeUnless { it == Int.MAX_VALUE }?.toString()))
                    return value
                }
            }
        }

        @JvmStatic fun decimal(name: String): Arg<BigDecimal> = decimal(name, null, null)
        @JvmStatic fun decimal(name: String, minimum: BigDecimal?, maximum: BigDecimal?): Arg<BigDecimal> {
            require(minimum == null || maximum == null || minimum <= maximum) { "minimum must not exceed maximum" }
            return object : Arg<BigDecimal>(name, false) {
                @Throws(ArgumentException::class)
                override fun parse(input: String, players: PlayerResolver): BigDecimal {
                    val value = try { BigDecimal(input) } catch (failure: NumberFormatException) {
                        throw ArgumentException(CommandMessageKeys.ARG_DECIMAL)
                    }
                    if (minimum != null && value < minimum || maximum != null && value > maximum) {
                        throw ArgumentException(CommandMessageKeys.ARG_DECIMAL_RANGE,
                            "minimum", minimum?.toPlainString() ?: "-∞", "maximum", maximum?.toPlainString() ?: "∞",
                            "range", range(minimum?.toPlainString(), maximum?.toPlainString()))
                    }
                    return value
                }
            }
        }

        @JvmStatic fun bool(name: String): Arg<Boolean> = object : Arg<Boolean>(name, false) {
            @Throws(ArgumentException::class)
            override fun parse(input: String, players: PlayerResolver): Boolean = when (input.lowercase(Locale.ROOT)) {
                "true", "yes", "on", "1" -> true
                "false", "no", "off", "0" -> false
                else -> throw ArgumentException(CommandMessageKeys.ARG_BOOLEAN)
            }
            override fun suggest(sender: CommandSender, prefix: String, players: PlayerResolver): List<String> =
                matching(booleanValues, prefix)
        }

        @JvmStatic fun <E : Enum<E>> enumeration(name: String, type: Class<E>): Arg<E> = object : Arg<E>(name, false) {
            @Throws(ArgumentException::class)
            override fun parse(input: String, players: PlayerResolver): E =
                type.enumConstants.firstOrNull { it.name.equals(input, ignoreCase = true) }
                    ?: throw ArgumentException(CommandMessageKeys.ARG_CHOICE, "choices", names(type).joinToString(", "))
            override fun suggest(sender: CommandSender, prefix: String, players: PlayerResolver): List<String> =
                matching(names(type), prefix)
        }

        @JvmStatic fun player(name: String): Arg<Player> = object : Arg<Player>(name, false) {
            @Throws(ArgumentException::class)
            override fun parse(input: String, players: PlayerResolver): Player = players.findExact(input)
                ?: throw ArgumentException(CommandMessageKeys.ARG_PLAYER_OFFLINE, "player", input)
            override fun suggest(sender: CommandSender, prefix: String, players: PlayerResolver): List<String> =
                matching(players.suggest(prefix), prefix)
        }

        @JvmStatic fun <T> contextual(name: String, parser: ContextualArgumentParser<T?>,
            suggestions: ContextualSuggestionProvider): Arg<T> = object : Arg<T>(name, false) {
            override fun parse(input: String, players: PlayerResolver): T = throw IllegalStateException("Context is required")
            @Throws(ArgumentException::class)
            override fun parse(input: String, players: PlayerResolver, context: CommandContext): T =
                customValue(input) { parser.parse(input, context) }
            override fun suggest(context: SuggestionContext, players: PlayerResolver): List<String> =
                matching(suggestions.suggest(context), context.prefix())
        }

        @JvmSynthetic
        fun <T> contextual(name: String, parser: ContextualArgumentParser<T>, suggestions: ContextualSuggestionProvider,
            @Suppress("UNUSED_PARAMETER") typed: Unit = Unit): Arg<T> =
            contextual(name, ContextualArgumentParser<T?> { input, context -> parser.parse(input, context) }, suggestions)

        @JvmStatic fun greedyString(name: String): Arg<String> = object : Arg<String>(name, true) {
            @Throws(ArgumentException::class)
            override fun parse(input: String, players: PlayerResolver): String = input.ifEmpty {
                throw ArgumentException(CommandMessageKeys.ARG_CONTENT_EMPTY)
            }
        }

        @JvmStatic fun choice(name: String, vararg choices: String): Arg<String> {
            require(choices.isNotEmpty()) { "choices must not be empty" }
            val values = choices.map { choice ->
                require(choice.trim().isNotEmpty()) { "choice must not be blank" }
                choice.trim()
            }
            return object : Arg<String>(name, false) {
                @Throws(ArgumentException::class)
                override fun parse(input: String, players: PlayerResolver): String =
                    values.firstOrNull { it.equals(input, ignoreCase = true) }
                        ?: throw ArgumentException(CommandMessageKeys.ARG_CHOICE, "choices", values.joinToString(", "))
                override fun suggest(sender: CommandSender, prefix: String, players: PlayerResolver): List<String> =
                    matching(values, prefix)
            }
        }

        @JvmStatic fun <T> optional(argument: Arg<T>, defaultValue: T?): Arg<T> {
            require(!argument.isGreedy) { "greedy argument cannot be optional" }
            return object : Arg<T>(argument.name(), false, true, defaultValue) {
                @Throws(ArgumentException::class)
                override fun parse(input: String, players: PlayerResolver): T = argument.parse(input, players)
                override fun isCatchAll(): Boolean = argument.isCatchAll
                override fun suggest(sender: CommandSender, prefix: String, players: PlayerResolver): List<String> =
                    argument.suggest(sender, prefix, players)
            }
        }

        private inline fun <T> customValue(input: String, parse: () -> T?): T = try {
            parse() ?: throw ArgumentException(CommandMessageKeys.UNKNOWN_ARGUMENT, "argument", input)
        } catch (failure: IllegalArgumentException) {
            throw ArgumentException(CommandMessageKeys.UNKNOWN_ARGUMENT, "argument", input)
        }

        private fun range(minimum: String?, maximum: String?): String = when {
            minimum != null && maximum != null -> "$minimum..$maximum"
            minimum != null -> "≥ $minimum"
            else -> "≤ $maximum"
        }
        private fun <E : Enum<E>> names(type: Class<E>): List<String> =
            type.enumConstants.map { it.name.lowercase(Locale.ROOT) }
        private fun matching(values: List<String?>?, prefix: String): List<String> = values.orEmpty()
            .filterNotNull().filter { it.lowercase(Locale.ROOT).startsWith(prefix.lowercase(Locale.ROOT)) }.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
}
