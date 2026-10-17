package me.kzheart.klib.remote

import java.util.Collections
import java.util.IdentityHashMap

/** 结构化异常图，分别保留 cause、suppressed 和每层堆栈。 */
class ThrowableSnapshot private constructor(values: Map<String, Any?>) {
    private val values = Collections.unmodifiableMap(values)
    fun toMap(): Map<String, Any?> = values

    private class Capture(private val budget: IncidentBudget) {
        private val seen = IdentityHashMap<Throwable, Boolean>()
        private var remaining = budget.throwableNodes()
        fun capture(error: Throwable, depth: Int): Map<String, Any?> {
            if (remaining < 1) return truncatedNodes()
            remaining--
            val result = linkedMapOf<String, Any?>("type" to error.javaClass.name, "message" to error.message)
            if (depth >= budget.throwableDepth()) return result.apply { put("truncated", "depth") }
            if (seen.put(error, true) != null) return result.apply { put("truncated", "reference") }
            val stack = error.stackTrace
            val frameLimit = minOf(stack.size, budget.framesPerThrowable())
            result["stack"] = stack.take(frameLimit).map {
                linkedMapOf("class" to it.className, "method" to it.methodName, "file" to it.fileName, "line" to it.lineNumber)
            }
            if (stack.size > frameLimit) result["stack_truncated"] = stack.size - frameLimit
            val suppressed = error.suppressed
            val suppressedLimit = minOf(suppressed.size, budget.suppressedPerThrowable())
            val encoded = ArrayList<Map<String, Any?>>()
            for (index in 0 until suppressedLimit) {
                if (remaining < 1) { encoded.add(truncatedNodes()); break }
                encoded.add(capture(suppressed[index], depth + 1))
            }
            result["suppressed"] = encoded
            if (suppressed.size > suppressedLimit) result["suppressed_truncated"] = suppressed.size - suppressedLimit
            error.cause?.let { result["cause"] = if (remaining > 0) capture(it, depth + 1) else truncatedNodes() }
            return result
        }
        private fun truncatedNodes(): Map<String, Any?> = linkedMapOf("truncated" to "nodes")
    }
    companion object {
        @JvmStatic fun capture(error: Throwable, budget: IncidentBudget): ThrowableSnapshot = ThrowableSnapshot(Capture(budget).capture(error, 0))
    }
}
