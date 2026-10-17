/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core

/** 动作分派、属性语法和容错字面量集中在一个解析入口。 */
internal object ActionReader {
    @JvmStatic fun <T> read(reader: SimpleReader, selectedNamespace: String?, allowLiteral: Boolean): ParsedAction<T> {
        reader.skipBlank()
        if (reader.index >= reader.content.size) throw LoadError.EOF.create()
        val start = reader.index
        val result = when (reader.peek()) {
            '{' -> anonymous(reader, selectedNamespace)
            '&' -> {
                reader.skip(1)
                reader.beforeParse()
                val variable = reader.nextToken()
                val bracket = propertyBracket(variable)
                if (bracket > 0) reader.wrap(CoreActions.PropertyGet(
                    reader.wrap(CoreActions.Get<Any?>(variable.substring(0, bracket))), variable.substring(bracket + 1, variable.length - 1)))
                else reader.wrap(CoreActions.Get<Any?>(variable))
            }
            '*' -> {
                reader.skip(1)
                reader.beforeParse()
                reader.wrap(CoreActions.Literal(reader.nextToken()))
            }
            else -> token(reader, selectedNamespace, allowLiteral)
        }
        result.set(ActionProperties.LINE, reader.blockParser.lineOf(start))
        result.set(ActionProperties.COLUMN, reader.blockParser.columnOf(start))
        @Suppress("UNCHECKED_CAST")
        return result as ParsedAction<T>
    }

    private fun anonymous(reader: SimpleReader, namespace: String?): ParsedAction<*> {
        val parser = reader.blockParser
        parser.index = reader.index
        val action = if (namespace == null) reader.nextAnonAction() else {
            parser.readAnonymousAction(namespaces(reader, namespace)).also { it.set(ActionProperties.REQUIRE_FRAME, true) }
        }
        reader.index = parser.index
        return action
    }

    private fun token(reader: SimpleReader, namespace: String?, allowLiteral: Boolean): ParsedAction<*> {
        val token = reader.nextTokenBlock()
        val element = token.token
        val tolerate = allowLiteral || reader.service.isToleranceParser
        if (tolerate && token.isBlock) {
            reader.beforeParse()
            return reader.wrap(CoreActions.Literal(element))
        }
        val domains = namespaces(reader, namespace)
        val bracket = if (token.isBlock) -1 else propertyBracket(element)
        val owner = if (bracket > 0) element.substring(0, bracket) else element
        val parser = reader.service.registry.getParser(owner, domains)
        reader.beforeParse()
        val action = when {
            parser.isPresent -> reader.wrap(parser.get().resolve<Any?>(reader))
            tolerate -> reader.wrap(CoreActions.Literal(owner, true))
            else -> throw LoadError.UNKNOWN_ACTION.create(owner)
        }
        return if (bracket > 0) reader.wrap(CoreActions.PropertyGet(action, element.substring(bracket + 1, element.length - 1))) else action
    }

    private fun namespaces(reader: SimpleReader, selected: String?): List<String> =
        LinkedHashSet<String>().apply { if (selected != null) add(selected); addAll(reader.namespace) }.toList()

    private fun propertyBracket(token: String): Int {
        if (!token.endsWith(']')) return -1
        return token.indexOf('[').takeIf { it >= 1 && it < token.length } ?: -1
    }
}
