package me.kzheart.klib.item.tag

/** 所有显式路径操作共用同一语法与容器校验，普通复合键不经过此解析器。 */
internal object NbtPath {
    private const val MAX_SEGMENTS = 512

    private sealed class Segment {
        data class Key(val name: String) : Segment()
        data class Index(val value: Int) : Segment()
    }

    fun get(root: NbtCompound, path: String): NbtTag? {
        val segments = parse(path)
        var current: NbtTag = root
        for (segment in segments) current = child(current, segment, path) ?: return null
        return current
    }

    fun put(root: NbtCompound, path: String, value: NbtTag) {
        val segments = parse(path)
        var current: NbtTag = root
        for (index in 0 until segments.lastIndex) {
            val segment = segments[index]
            val next = child(current, segment, path)
            if (next == null) {
                // 缺失分支只在隔离对象中构建；所有后续步骤成功后才一次发布。
                val branch = missingBranch(segments, index + 1, value, path)
                compound(current, path).put((segment as Segment.Key).name, branch)
                return
            }
            current = next
        }
        when (val last = segments.last()) {
            is Segment.Key -> compound(current, path).put(last.name, value)
            is Segment.Index -> list(current, last.value, path)[last.value] = value
        }
    }

    fun remove(root: NbtCompound, path: String): NbtTag? {
        val segments = parse(path)
        var current: NbtTag = root
        for (index in 0 until segments.lastIndex) {
            current = child(current, segments[index], path) ?: return null
        }
        return when (val last = segments.last()) {
            is Segment.Key -> compound(current, path).remove(last.name)
            is Segment.Index -> list(current, last.value, path).removeAt(last.value)
        }
    }

    private fun child(parent: NbtTag, segment: Segment, path: String): NbtTag? = when (segment) {
        is Segment.Key -> compound(parent, path)[segment.name]
        is Segment.Index -> list(parent, segment.value, path)[segment.value]
    }

    private fun compound(tag: NbtTag, path: String): NbtCompound {
        check(tag is NbtCompound) { "NBT path '$path' expected COMPOUND but found ${tag.type}" }
        return tag
    }

    private fun list(tag: NbtTag, index: Int, path: String): NbtList {
        check(tag is NbtList) { "NBT path '$path' expected LIST but found ${tag.type}" }
        if (index >= tag.size()) {
            throw IndexOutOfBoundsException("NBT path '$path' index $index out of bounds for list size ${tag.size()}")
        }
        return tag
    }

    private fun missingBranch(segments: List<Segment>, start: Int, value: NbtTag, path: String): NbtCompound {
        if ((start..segments.lastIndex).any { segments[it] is Segment.Index }) {
            throw NoSuchElementException("NBT path '$path' requires an existing list; lists are not created or extended")
        }
        val root = NbtCompound()
        var current = root
        for (index in start until segments.lastIndex) {
            val key = (segments[index] as Segment.Key).name
            current.putCompound(key, NbtCompound())
            current = current.getCompound(key)!!
        }
        current.put((segments.last() as Segment.Key).name, value)
        return root
    }

    /** key ('.' key | '[' digits ']')*；键中仅 . [ ] 和反斜线可用反斜线转义。 */
    private fun parse(path: String): List<Segment> {
        fun invalid(at: Int): Nothing = throw IllegalArgumentException("Invalid NBT path '$path' at offset $at")
        if (path.isEmpty()) invalid(0)
        val result = ArrayList<Segment>()
        fun append(segment: Segment) {
            require(result.size < MAX_SEGMENTS) { "NBT path exceeds $MAX_SEGMENTS segments" }
            result.add(segment)
        }
        var cursor = 0
        while (cursor < path.length) {
            val key = StringBuilder()
            while (cursor < path.length && path[cursor] != '.' && path[cursor] != '[') {
                when (val char = path[cursor++]) {
                    ']' -> invalid(cursor - 1)
                    '\\' -> {
                        if (cursor == path.length || path[cursor] !in ".[]\\") invalid(cursor - 1)
                        key.append(path[cursor++])
                    }
                    else -> key.append(char)
                }
            }
            if (key.isEmpty()) invalid(cursor)
            append(Segment.Key(key.toString()))
            while (cursor < path.length && path[cursor] == '[') {
                val opening = cursor++
                val start = cursor
                while (cursor < path.length && path[cursor] in '0'..'9') cursor++
                if (cursor == start || cursor == path.length || path[cursor] != ']') invalid(opening)
                val index = path.substring(start, cursor).toIntOrNull() ?: invalid(opening)
                append(Segment.Index(index))
                cursor++
            }
            if (cursor < path.length) {
                if (path[cursor] != '.') invalid(cursor)
                cursor++
                if (cursor == path.length) invalid(cursor)
            }
        }
        return result
    }
}
