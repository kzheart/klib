/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core

import java.util.Arrays
import java.util.concurrent.atomic.AtomicLong

/** 块组装和源位置索引不再暴露给宿主扩展；扩展仍通过 BlockReader 的既有钩子参与。 */
internal class BlockParser(private val reader: BlockReader) {
    private val lineStarts = buildList {
        add(0)
        reader.content.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
    }.toIntArray()

    fun parse(id: String): Quest {
        while (reader.hasNext()) reader.readBlock()
        return SimpleQuest(reader.content, reader.blocks, id)
    }
    fun readBlock() {
        reader.expect("def")
        val name = reader.nextToken()
        reader.expect("=")
        reader.currentBlock = name
        val actions = reader.readActions()
        reader.checkLiteral(actions)
        val block = SimpleQuest.SimpleBlock(name, actions)
        reader.processActions(block, actions)
        reader.blocks[name] = block
    }
    fun checkLiteral(actions: List<ParsedAction<*>>) {
        if (!reader.service.isToleranceParser) return
        var previous: CoreActions.Literal<*>? = null
        for (action in actions) {
            previous?.let { throw LocalizedException.of("isolated-literal", it.getValue().toString()) }
            val literal = action.action as? CoreActions.Literal<*>
            if (literal?.isMisspelled() == true) previous = literal
        }
    }
    fun readActions(namespaces: List<String>): List<ParsedAction<*>> {
        reader.skipBlank()
        val batch = reader.peek() == '{'
        if (batch) reader.skip(1)
        val actionReader = reader.newActionReader(reader.service, namespaces)
        try {
            val actions = ArrayList<ParsedAction<*>>()
            while ((batch && actionReader.hasNext()) || actions.isEmpty()) {
                if (batch && actionReader.peek() == '}') {
                    actionReader.skip(1)
                    reader.index = actionReader.index
                    actions.trimToSize()
                    return actions
                }
                actions.add(actionReader.nextAction<Any?>())
                actionReader.mark()
            }
            reader.index = actionReader.index
            return actions
        } catch (failure: LocalizedException) {
            val from = actionReader.getMark().coerceIn(0, reader.content.size)
            val to = actionReader.getIndex().coerceIn(from, reader.content.size)
            val source = String(reader.content, from, to - from).trim { it <= ' ' }
            throw LoadError.BLOCK_ERROR.create(reader.currentBlock, lineOf(from), source).then(failure)
        } catch (failure: RuntimeException) {
            throw LoadError.UNHANDLED.create(failure)
        }
    }
    fun readAnonymousAction(namespaces: List<String>): ParsedAction<*> {
        val previous = reader.currentBlock
        val name = reader.nextAnonymousBlockName()
        reader.currentBlock = name
        val actions = readActions(namespaces)
        reader.checkLiteral(actions)
        reader.currentBlock = previous
        if (actions.isEmpty()) return ParsedAction.noop<Any?>()
        val head = actions[0]
        val block = SimpleQuest.SimpleBlock(name, actions)
        reader.blocks[name] = block
        head.set(ActionProperties.BLOCK, block.label)
        return head
    }
    fun nextAnonymousBlockName(): String = "${reader.currentBlock}_anon_${anonymousIds.incrementAndGet()}"
    fun lineOf(index: Int): Int {
        val found = Arrays.binarySearch(lineStarts, index.coerceAtLeast(0))
        return if (found >= 0) found + 1 else -found - 1
    }
    fun columnOf(index: Int): Int = (index - lineStarts[lineOf(index) - 1] + 1).coerceAtLeast(1)
    companion object { private val anonymousIds = AtomicLong() }
}
