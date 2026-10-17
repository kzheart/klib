package me.kzheart.klib.script

import java.util.function.Supplier
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager
import javax.script.ScriptException
import javax.script.SimpleBindings

/** Java 8 JSR-223 薄适配器，隐藏引擎发现、绑定隔离与释放。 */
class ScriptJavaScriptEngines private constructor() {
    private class EngineEvaluator(private var engine: ScriptEngine?) : JavaScriptEvaluator, AutoCloseable {
        override fun eval(source: String, bindings: Map<String, Any?>): Any? {
            val active = engine ?: throw IllegalStateException("JavaScript evaluator is closed")
            return try { active.eval(source, SimpleBindings(LinkedHashMap(bindings))) }
                catch (failure: ScriptException) { throw IllegalArgumentException("JavaScript evaluation failed: ${failure.message}", failure) }
        }
        override fun close() {
            val closing = engine
            engine = null
            if (closing is AutoCloseable) try { closing.close() }
            catch (failure: Exception) {
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                throw IllegalStateException("Failed to close the JavaScript engine", failure)
            }
        }
    }
    companion object {
        @JvmStatic fun named(engineName: String, loader: ClassLoader): Supplier<JavaScriptEvaluator> {
            val name = engineName.trim { it <= ' ' }
            require(name.isNotEmpty()) { "JavaScript engine name must not be blank" }
            return Supplier {
                val engine = try { ScriptEngineManager(loader).getEngineByName(name) }
                    catch (failure: RuntimeException) { throw initializationFailure(name, failure) }
                    catch (failure: LinkageError) { throw initializationFailure(name, failure) }
                check(engine != null) { "JavaScript engine '$name' is not available. Provide a compatible JSR-223 engine or configure an explicit JavaScript evaluator factory." }
                using(engine)
            }
        }
        @JvmStatic fun using(engine: ScriptEngine): JavaScriptEvaluator = EngineEvaluator(engine)
        private fun initializationFailure(name: String, failure: Throwable): IllegalStateException =
            IllegalStateException("Failed to initialize JavaScript engine '$name'. Check its runtime dependencies.", failure)
    }
}
