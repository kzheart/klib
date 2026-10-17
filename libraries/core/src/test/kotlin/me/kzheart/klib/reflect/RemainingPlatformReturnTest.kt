package me.kzheart.klib.reflect

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RemainingPlatformReturnTest {
    class Target {
        fun missing(): Any? = null
        fun present(): Any = "value"
        fun noResult() { }
    }

    // 重复判空后的直接使用是既有 Java 平台类型调用方式，不能被显式 nullable 收紧。
    private fun guarded(target: Target, name: String): String =
        if (Declarations.invoke(target, Target::class.java.getMethod(name)) != null)
            Declarations.invoke(target, Target::class.java.getMethod(name)).javaClass.simpleName else "missing"

    @Test fun reflectionKeepsPlatformInferenceAndRealNull() {
        val target = Target()
        assertEquals("missing", guarded(target, "missing"))
        assertEquals("String", guarded(target, "present"))
        assertNull(Declarations.invoke(target, Target::class.java.getMethod("noResult")))
        val present: Any = Declarations.invoke(target, Target::class.java.getMethod("present"))
        assertEquals("value", present)
    }
}
