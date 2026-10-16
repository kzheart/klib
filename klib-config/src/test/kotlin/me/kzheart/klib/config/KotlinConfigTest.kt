package me.kzheart.klib.config

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import me.kzheart.klib.config.annotation.Comment
import me.kzheart.klib.config.annotation.ConfigFile
import me.kzheart.klib.config.annotation.Key
import me.kzheart.klib.config.annotation.Range
import me.kzheart.klib.config.annotation.Validate
import me.kzheart.klib.config.api.Configs
import me.kzheart.klib.scope.ScopeImpl
import me.kzheart.klib.scope.capability.ConfigCapability
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KotlinConfigTest {
    private val mapper = YamlConfigMapper()

    data class Required(val name: String, val nullable: String?, val retries: Int = 3)
    data class Endpoint(@field:Range(min = 1.0, max = 65535.0) val port: Int = 25565)

    @ConfigFile("settings.yml")
    data class Settings(
        @field:Key("display-name") @field:Comment("显示名称", "支持中文 😀")
        val name: String = "你好 😀",
        val endpoint: Endpoint = Endpoint(),
        val note: String? = "fallback",
        val timeout: Duration = Duration.ofSeconds(30),
        val names: List<String?> = emptyList(),
        val hosts: Map<String, Endpoint?> = emptyMap(),
    )

    @Test
    fun `required constructor values use names without no arg constructor`() {
        val result = mapper.read<Required>(node("name: live\nnullable: null\n"))
        assertEquals(Required("live", null), result)
    }

    @Test
    fun `missing nullable without default is still required`() {
        val error = assertThrows(ConfigMappingException::class.java) {
            mapper.read<Required>(node("name: live\n"))
        }
        assertEquals("nullable", error.path())
        assertTrue(error.message!!.contains("required"))
    }

    @Test
    fun `missing non null without default is required`() {
        val error = assertThrows(ConfigMappingException::class.java) {
            mapper.read<Required>(node("nullable: null\n"))
        }
        assertEquals("name", error.path())
    }

    @Test
    fun `optional parameters execute their real Kotlin defaults`() {
        assertEquals(Settings(), mapper.read<Settings>(node("{}")))
        assertEquals("fallback", mapper.read<Settings>(node("display-name: custom\n")).note)
    }

    @Test
    fun `explicit nullable null overrides a non null default`() {
        val result = mapper.read<Settings>(node("note: null\n"))
        assertNull(result.note)
    }

    @Test
    fun `explicit null never falls back to a non null default`() {
        val error = assertThrows(ConfigMappingException::class.java) {
            mapper.read<Settings>(node("display-name: null\n"))
        }
        assertEquals("display-name", error.path())
        assertEquals(1, error.line())
        assertTrue(error.hasLocation())
        assertTrue(error.message!!.contains("non-null"))
    }

    @Test
    fun `nested model and nullable collection entries preserve types`() {
        val result = mapper.read<Settings>(node("""
            endpoint:
              port: 8080
            timeout: 1m30s
            names: [one, null, '中文 😀']
            hosts:
              lobby:
                port: 8123
              disabled: null
        """.trimIndent()))
        assertEquals(Endpoint(8080), result.endpoint)
        assertEquals(Duration.ofSeconds(90), result.timeout)
        assertEquals(listOf("one", null, "中文 😀"), result.names)
        assertEquals(mapOf("lobby" to Endpoint(8123), "disabled" to null), result.hosts)
    }

    data class StrictCollections(
        val names: List<String> = emptyList(),
        val nested: List<List<String>> = emptyList(),
        val endpoints: Map<String, Endpoint> = emptyMap(),
        val array: Array<String> = emptyArray(),
    )

    @Test
    fun `collection element nullability reports element paths`() {
        for ((yaml, path) in listOf(
            "names: [one, null]\n" to "names[1]",
            "nested: [[one, null]]\n" to "nested[0][1]",
            "endpoints: {lobby: null}\n" to "endpoints.lobby",
            "array: [one, null]\n" to "array[1]",
        )) {
            val error = assertThrows(ConfigMappingException::class.java) {
                mapper.read<StrictCollections>(node(yaml))
            }
            assertEquals(path, error.path())
        }
    }

    data class NullableContainers(
        val names: List<String>? = listOf("default"),
        val endpoints: Map<String, Endpoint>? = emptyMap(),
        val array: Array<String?> = emptyArray(),
        val numbers: IntArray = intArrayOf(),
    )

    @Test
    fun `nullable containers null and object primitive arrays are mapped`() {
        val result = mapper.read<NullableContainers>(node("names: null\nendpoints: null\narray: [ok, null]\nnumbers: [1, 2]\n"))
        assertNull(result.names)
        assertNull(result.endpoints)
        assertEquals(listOf("ok", null), result.array.toList())
        assertEquals(listOf(1, 2), result.numbers.toList())
    }

    @Test
    fun `wrong nested scalar retains original YAML location`() {
        val error = assertThrows(ConfigMappingException::class.java) {
            mapper.read<Settings>(node("endpoint:\n  port: wrong\n"))
        }
        assertEquals("endpoint.port", error.path())
        assertEquals(2, error.line())
        assertTrue(error.column() > 0)
        assertEquals("settings.yml", error.sourceName())
    }

    @Test
    fun `range validates constructor values at both boundaries`() {
        assertEquals(1, mapper.read<Endpoint>(node("port: 1\n")).port)
        assertEquals(65535, mapper.read<Endpoint>(node("port: 65535\n")).port)
        for (port in listOf(0, 65536)) {
            val error = assertThrows(ConfigMappingException::class.java) {
                mapper.read<Settings>(node("endpoint: {port: $port}\n"))
            }
            assertEquals("endpoint.port", error.path())
        }
    }

    data class InvalidDefault(@field:Range(min = 1.0, max = 5.0) val count: Int = 0)

    @Test
    fun `omitted Kotlin defaults still undergo range validation`() {
        assertThrows(ConfigMappingException::class.java) { mapper.read<InvalidDefault>(node("{}")) }
        assertThrows(ConfigException::class.java) { configDefaults<InvalidDefault>() }
    }

    data class Checked(val start: Int = 0, val end: Int = 1) {
        @Validate fun validate() {
            require(start <= end) { "start exceeds end" }
        }
    }

    @Test
    fun `validate methods run after constructor mapping`() {
        val error = assertThrows(ConfigMappingException::class.java) {
            mapper.read<Checked>(node("start: 3\nend: 1\n"))
        }
        assertTrue(error.message!!.contains("start exceeds end"))
    }

    data class ConstructorCheck(val endpoint: Int = 1) {
        init { require(endpoint > 0) { "endpoint must be positive" } }
    }

    data class NestedConstructorCheck(val nested: ConstructorCheck = ConstructorCheck())

    @Test
    fun `constructor failures retain nested path and original cause`() {
        val error = assertThrows(ConfigMappingException::class.java) {
            mapper.read<NestedConstructorCheck>(node("nested: {endpoint: 0}\n"))
        }
        assertEquals("nested", error.path())
        assertTrue(error.cause is IllegalArgumentException)
        assertTrue(error.message!!.contains("endpoint must be positive"))
    }

    data class Duplicate(@field:Key("same") val first: Int = 1, @field:Key("same") val second: Int = 2)

    @Test
    fun `mapper and defaults reject the same duplicate metadata`() {
        assertThrows(ConfigMappingException::class.java) { mapper.read<Duplicate>(node("{}")) }
        assertThrows(ConfigException::class.java) { configDefaults<Duplicate>() }
    }

    data class BodyProperties(val required: String) {
        var title: String = "body-default"
        var nullable: String? = "body-default"
        val fixed: String = "fixed"
    }

    @Test
    fun `mutable body properties map with their own nullability`() {
        val result = mapper.read<BodyProperties>(node("required: present\ntitle: changed\nnullable: null\n"))
        assertEquals("changed", result.title)
        assertNull(result.nullable)
        val error = assertThrows(ConfigMappingException::class.java) {
            mapper.read<BodyProperties>(node("required: present\ntitle: null\n"))
        }
        assertEquals("title", error.path())
        assertThrows(ConfigMappingException::class.java) {
            mapper.read<BodyProperties>(node("required: present\nfixed: changed\n"))
        }
    }

    @Test
    fun `default YAML shares Kotlin fields aliases comments and validation`() {
        val yaml = configDefaults<Settings>()
        assertTrue(yaml.contains("# 显示名称"))
        assertTrue(yaml.contains("# 支持中文 😀"))
        assertTrue(yaml.contains("display-name"))
        assertFalse(yaml.contains("Companion"))
        assertEquals(Settings(), mapper.read<Settings>(node(yaml)))
        assertEquals(yaml, configDefaults(Settings::class))
    }

    @Test
    fun `default YAML requires actual defaults even for nullable parameters`() {
        val error = assertThrows(ConfigException::class.java) { configDefaults<Required>() }
        assertTrue(error.message!!.contains("required constructor parameter"))
    }

    @Test
    fun `ValueDocument uses identical constructor mapping and diagnostics`() {
        val source = ValueDocument.of("settings.json", mapOf("endpoint" to mapOf("port" to "wrong"))) { _, _ -> "{}" }
        val error = assertThrows(ConfigMappingException::class.java) { mapper.read<Settings>(source.root()) }
        assertEquals("endpoint.port", error.path())
        assertEquals("settings.json", error.sourceName())
        assertFalse(error.hasLocation())
    }

    @Test
    fun `custom converters continue to work inside Kotlin constructor models`() {
        val custom = YamlConfigMapper().registerConverter(Endpoint::class.java) { child ->
            if (child.raw() == "default") Endpoint(9000) else throw child.mappingError("unknown endpoint")
        }
        assertEquals(9000, custom.read<Settings>(node("endpoint: default\n")).endpoint.port)
    }

    @Test
    fun `converter null cannot violate Kotlin non null constructor parameters`() {
        val custom = YamlConfigMapper().registerConverter(Endpoint::class.java) { null }
        val error = assertThrows(ConfigMappingException::class.java) {
            custom.read<Settings>(node("endpoint: anything\n"))
        }
        assertEquals("endpoint", error.path())
    }

    @Test
    fun `unknown aliases warn only once while declared constructor keys are accepted`() {
        val records = mutableListOf<LogRecord>()
        val logger = Logger.getLogger(YamlConfigMapper::class.java.name)
        val collector = object : Handler() {
            override fun publish(record: LogRecord) { records += record }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(collector)
        try {
            repeat(2) { mapper.read<Settings>(node("display-name: valid\nunknown: 1\n")) }
            assertEquals(1, records.count { it.message.contains("unknown configuration key") })
            assertTrue(records.single().message.contains("unknown"))
        } finally {
            logger.removeHandler(collector)
        }
    }

    @Test
    fun `native load overloads use the existing scope owned capability`() {
        val scope = ScopeImpl("kotlin-config")
        val source = InMemoryConfigSource("settings.yml", "display-name: loaded\n")
        scope.registerCapability(ConfigCapability::class.java, YamlConfigCapability({ _, path ->
            assertEquals("settings.yml", path)
            source
        }, mapper))
        try {
            val configs = Configs(scope)
            assertEquals("loaded", configs.load<Settings>().value().name)
            assertEquals("loaded", configs.load<Settings>("settings.yml").value().name)
            assertEquals("loaded", configs.load(Settings::class).value().name)
            assertEquals("loaded", configs.load(Settings::class, "settings.yml").value().name)
        } finally {
            scope.close()
        }
    }

    @Test
    fun `failed Kotlin reload preserves exact snapshot and skips notifications`() {
        val scope = ScopeImpl("kotlin-atomic")
        val source = InMemoryConfigSource("settings.yml", "display-name: first\n")
        val document = YamlConfigDocument.open(scope, source, mapper, Settings::class.java)
        val changes = AtomicInteger()
        document.onChange { changes.incrementAndGet() }
        val previous = document.value()
        try {
            for (invalid in listOf("endpoint: {port: wrong}\n", "display-name: null\n", "endpoint: {port: 0}\n")) {
                source.update(invalid)
                assertThrows(ConfigException::class.java) { document.reload() }
                assertSame(previous, document.value())
                assertEquals(0, changes.get())
                assertTrue(document.lastFailure().isPresent)
            }
            source.update("display-name: second\n")
            document.reload()
            assertEquals("second", document.value().name)
            assertEquals(1, changes.get())
            assertFalse(document.lastFailure().isPresent)
        } finally {
            scope.close()
        }
        assertThrows(IllegalStateException::class.java) { document.reload() }
    }

    data class ObjectCollectionDefaults(
        val endpoints: List<Endpoint> = listOf(Endpoint(8000), Endpoint(9000)),
        val named: Map<String, Endpoint?> = mapOf("main" to Endpoint(8080), "off" to null),
    )

    @Test
    fun `model collection defaults round trip through the shared field plan`() {
        val yaml = configDefaults<ObjectCollectionDefaults>()
        assertEquals(ObjectCollectionDefaults(), mapper.read<ObjectCollectionDefaults>(node(yaml)))
    }

    class LateinitBody {
        lateinit var required: String
    }

    @Test
    fun `uninitialized non null body properties fail with their path`() {
        val error = assertThrows(ConfigMappingException::class.java) { mapper.read<LateinitBody>(node("{}")) }
        assertEquals("required", error.path())
        assertEquals("present", mapper.read<LateinitBody>(node("required: present\n")).required)
    }

    @Test
    fun `native non null node read preserves node diagnostics`() {
        val error = assertThrows(ConfigMappingException::class.java) {
            mapper.read<Settings>(node("settings: null\n").child("settings"))
        }
        assertEquals("settings.yml", error.sourceName())
        assertEquals("settings", error.path())
    }

    data class DependentDefault(val base: Int = 3, val doubled: Int = base * 2)

    @Test
    fun `defaults depending on supplied arguments run inside the actual constructor`() {
        assertEquals(DependentDefault(5, 10), mapper.read<DependentDefault>(node("base: 5\n")))
        assertEquals(DependentDefault(5, 99), mapper.read<DependentDefault>(node("base: 5\ndoubled: 99\n")))
    }

    data class IgnoredDefault(
        val value: String = "default",
        @Transient val cache: String = "local",
    )

    @Test
    fun `transient constructor properties keep their defaults and stay out of generated YAML`() {
        val value = mapper.read<IgnoredDefault>(node("value: configured\ncache: external\n"))
        assertEquals("configured", value.value)
        assertEquals("local", value.cache)
        assertFalse(configDefaults<IgnoredDefault>().contains("cache"))
    }

    class PrivateConstructor private constructor(val value: String = "default")

    @Test
    fun `private primary constructors preserve existing non public mapping support`() {
        assertEquals("configured", mapper.read<PrivateConstructor>(node("value: configured\n")).value)
        assertEquals("default", mapper.read<PrivateConstructor>(node(configDefaults<PrivateConstructor>())).value)
    }

    class CyclicDefaults(val values: MutableList<Any> = mutableListOf()) {
        init { values.add(values) }
    }

    @Test
    fun `cyclic collection defaults fail with ConfigException rather than overflowing`() {
        val error = assertThrows(ConfigException::class.java) { configDefaults<CyclicDefaults>() }
        assertTrue(error.message!!.contains("Cyclic"))
    }

    private fun node(yaml: String): ConfigNode = YamlDocument.parse("settings.yml", yaml).root()
}
