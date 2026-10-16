package me.kzheart.klib.script

import kotlinx.coroutines.future.await

/** Uses the existing engine and propagates cancellation to its completion future. */
suspend fun ScriptEngine.evalAwait(script: String, context: ScriptContext): Any? = eval(script, context).await()
suspend fun ScriptEngine.evalConditionAwait(script: String, context: ScriptContext): Boolean =
    evalCondition(script, context).await()

fun scriptContext(block: ScriptContext.Builder.() -> Unit): ScriptContext =
    ScriptContext.builder().apply(block).build()
