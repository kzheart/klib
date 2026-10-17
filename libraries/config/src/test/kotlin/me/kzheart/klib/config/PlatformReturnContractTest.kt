package me.kzheart.klib.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlatformReturnContractTest {
    private fun rawType(node: ConfigNode): String =
        if (node.raw() != null) node.raw().javaClass.name else "null"

    @Test fun `missing and explicit null nodes retain Java platform returns`() {
        val yaml = YamlDocument.parse("test.yml", "value: null\nnumber: 3")
        assertEquals("null", rawType(yaml.node("missing")))
        assertEquals("null", rawType(yaml.node("value")))
        assertEquals("java.lang.Integer", rawType(yaml.node("number")))
        val values = ValueDocument.of("test.json", mapOf("value" to null)) { _, _ -> "{}" }
        assertNull(values.node("value").raw())
        assertNull(values.node("missing").raw())
    }

    @Test fun `node writer receives the original view and nested values preserve identity`() {
        var received: ConfigNode? = null
        val values = ValueDocument.ofWithNodeWriter("test.json", mapOf("nested" to mapOf("x" to 1))) { node, _ ->
            received = node
            "{}"
        }
        val node = values.node("nested")
        assertEquals("{}", node.sectionText())
        assertSame(node, received)
        assertTrue(node.sameNode(values.node("nested")))
        assertSame(node, node.node("."))
        assertEquals("java.lang.Integer", rawType(node.child("x")))
    }
}
