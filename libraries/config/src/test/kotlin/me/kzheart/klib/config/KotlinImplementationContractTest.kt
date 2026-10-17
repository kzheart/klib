package me.kzheart.klib.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KotlinImplementationContractTest {
    private class Endpoint(val port: Int)
    private data class Settings(val endpoint: Endpoint)

    @Test fun `typed Java converter variables and nullable converter lambdas remain accepted`() {
        val converter = ConfigConverter<Endpoint> { Endpoint(9000) }
        val mapper = YamlConfigMapper().registerConverter(Endpoint::class.java, converter)
        assertEquals(9000, mapper.read(YamlDocument.parse("settings.yml", "endpoint: default").root(), Settings::class.java).endpoint.port)
        mapper.registerConverter(Endpoint::class.java) { null }
        assertThrows(ConfigMappingException::class.java) {
            mapper.read(YamlDocument.parse("settings.yml", "endpoint: default").root(), Settings::class.java)
        }
        assertNull(mapper.read(YamlDocument.parse("settings.yml", "endpoint: default").node("endpoint"), Endpoint::class.java))
    }

    @Test fun `mutable value containers are copied while views retain node identity`() {
        val original = linkedMapOf<String, Any?>("nested" to linkedMapOf("x" to 1))
        val document = ValueDocument.of("settings.json", original) { _, _ -> "{}" }
        val old = document.node("nested")
        document.root().createSection("nested")
        assertEquals(setOf("x"), old.keys())
        assertEquals(emptySet<String>(), document.node("nested").keys())
        assertEquals(setOf("x"), (original["nested"] as Map<*, *>).keys)
        assertEquals(false, old.sameNode(document.node("nested")))
    }
}
