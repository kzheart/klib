package me.kzheart.klib.component

import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicLong
import me.kzheart.klib.reflect.Declarations
import me.kzheart.klib.scope.Disposable
import me.kzheart.klib.scope.Scope

class Components(private val owner: Scope) {
    fun install(component: Any): ComponentHandle {
        val methods = Declarations.methods(component.javaClass)
        val start = lifecycleMethod(methods, OnStart::class.java)
        val stop = lifecycleMethod(methods, OnStop::class.java)
        val scope = owner.scope("component-${ids.incrementAndGet()}") { child ->
            (component as? KComponent)?.attach(KContext(child))
            // Installed first: invoked only after child registrations are released.
            stop?.let { method -> child.install(Disposable { Declarations.invoke(component, method) }) }
            start?.let { Declarations.invoke(component, it) }
            (component as? KComponent)?.initialize()
        }
        return ComponentHandle(scope)
    }
    private fun lifecycleMethod(methods: List<Method>, annotation: Class<out Annotation>): Method? {
        var found: Method? = null
        for (method in methods) {
            if (!method.isAnnotationPresent(annotation)) continue
            Declarations.voidMethod(method, 0)
            if (found != null) throw Declarations.invalid(method, "multiple @${annotation.simpleName} methods")
            found = method
        }
        return found
    }
    companion object { private val ids = AtomicLong() }
}
