/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core

import java.util.Collections
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.function.BiFunction
import java.util.function.Function

/** 解析阶段只构造动作；执行阶段按组合器约定汇合，回溯游标始终局部保存。 */
internal object ParserCombinators {
    @JvmStatic fun <T> orElse(first: Parser<T>, other: Parser<T>): Parser<T> = Parser.frame { reader ->
        val position = reader.index
        try { first.read(reader) } catch (_: RuntimeException) {
            reader.index = position
            other.read(reader)
        }
    }

    @JvmStatic fun <T> optional(parser: Parser<T>): Parser<Optional<T>> =
        map(parser, Function { value -> optionalValue(value) }).orElse(Parser.point(optionalValue(null)))

    @Suppress("UNCHECKED_CAST")
    private fun <T> optionalValue(value: T?): Optional<T> = Optional.ofNullable(value) as Optional<T>

    @JvmStatic fun <T> listOf(parser: Parser<T>): Parser<List<T>> = Parser.frame { reader ->
        reader.expect("[")
        val actions = ArrayList<Parser.Action<T>>()
        while (reader.hasNext() && reader.peek() != ']') actions.add(parser.read(reader))
        reader.expect("]")
        Parser.Action { frame ->
            val futures = actions.map { it.run(frame) }
            CompletableFuture.allOf(*futures.toTypedArray()).thenApply {
                Collections.unmodifiableList(futures.map { it.join() })
            }
        }
    }

    @JvmStatic fun <T, R> map(parser: Parser<T>, function: Function<in T, out R>): Parser<R> = Parser.frame { reader ->
        val action = parser.read(reader)
        Parser.Action { frame -> action.run(frame).thenApply(function) }
    }

    @JvmStatic fun <T, B, R> fold(first: Parser<T>, second: Parser<B>, function: BiFunction<T, B, R>): Parser<R> =
        Parser.frame { reader ->
            val firstAction = first.read(reader)
            val secondAction = second.read(reader)
            Parser.Action { frame ->
                firstAction.run(frame).thenCompose { left ->
                    secondAction.run(frame).thenApply { right -> function.apply(left, right) }
                }
            }
        }

    @JvmStatic fun <T, B, C, R> fold(first: Parser<T>, second: Parser<B>, third: Parser<C>, function: Parser.Function3<T, B, C, R>): Parser<R> =
        first.fold(second, BiFunction { a, b -> Pair(a, b) }).fold(third, BiFunction { pair, c -> function.apply(pair.first, pair.second, c) })

    @JvmStatic fun <T, B, C, D, R> fold(first: Parser<T>, second: Parser<B>, third: Parser<C>, fourth: Parser<D>, function: Parser.Function4<T, B, C, D, R>): Parser<R> =
        first.fold(second, third, Parser.Function3 { a, b, c -> Triple(a, b, c) }).fold(fourth,
            BiFunction { values, d -> function.apply(values.first, values.second, values.third, d) })

    @JvmStatic fun <T, B, C, D, E, R> fold(first: Parser<T>, second: Parser<B>, third: Parser<C>, fourth: Parser<D>, fifth: Parser<E>, function: Parser.Function5<T, B, C, D, E, R>): Parser<R> =
        first.fold(second, third, Parser.Function3 { a, b, c -> Triple(a, b, c) }).fold(fourth,
            BiFunction { values, d -> Pair(values, d) }).fold(fifth,
            BiFunction { values, e -> function.apply(values.first.first, values.first.second, values.first.third, values.second, e) })

    @JvmStatic fun <A> build(parser: Parser<Parser.Action<A>>): QuestActionParser = object : QuestActionParser {
        override fun <R> resolve(resolver: QuestReader): QuestAction<R> {
            val outer = parser.read(resolver)
            return object : QuestAction<R>() {
                @Suppress("UNCHECKED_CAST")
                override fun process(frame: QuestContext.Frame): CompletableFuture<R> =
                    outer.run(frame).thenCompose { it.run(frame) as CompletableFuture<R> }
            }
        }
    }
}
