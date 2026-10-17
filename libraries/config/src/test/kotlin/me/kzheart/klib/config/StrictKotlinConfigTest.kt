package me.kzheart.klib.config

import me.kzheart.klib.config.annotation.Key
import me.kzheart.klib.scope.ScopeImpl
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StrictKotlinConfigTest {
    data class Endpoint(val port: Int = 25565)
    data class Settings(
        @field:Key("display-name") val name: String = "Shop",
        val endpoints: Map<String, Endpoint> = emptyMap(),
    )

    private val mapper = YamlConfigMapper(UnknownKeyPolicy.REJECT)

    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `strict policy shares Kotlin aliases defaults and nested type plans`() {
        assertEquals(Settings("Codex"), mapper.read<Settings>(
            YamlDocument.parse("config.yml", "display-name: Codex\n").root()))
        val failure = assertThrows(ConfigMappingException::class.java) {
            mapper.read<Settings>(YamlDocument.parse("config.yml",
                "endpoints:\n  lobby:\n    prot: 25565\n").root())
        }
        assertEquals("endpoints.lobby.prot", failure.path())
        assertEquals(3, failure.line())
        assertEquals(5, failure.column())
    }

    @Test
    fun `strict nested Kotlin failure preserves published identity and skips listeners`() {
        val scope = ScopeImpl("strict-kotlin-config")
        try {
            val source = InMemoryConfigSource("config.yml", "display-name: Old\n")
            val document = YamlConfigDocument.open(scope, source, mapper, Settings::class.java)
            val previous = document.value()
            var notifications = 0
            document.onChange { notifications++ }
            source.update("display-name: New\nendpoints: {lobby: {prot: 25565}}\n")
            assertThrows(ConfigMappingException::class.java) { document.reload() }
            assertSame(previous, document.value())
            assertEquals(0, notifications)
            source.update("display-name: New\nendpoints: {lobby: {port: 25566}}\n")
            document.reload()
            assertEquals(25566, document.value().endpoints.getValue("lobby").port)
            assertEquals(1, notifications)
        } finally {
            scope.close()
        }
    }

    @Test
    fun `Kotlin installs mapper with and without migration provider`() {
        val resources = temporaryDirectory.resolve("resources")
        Files.createDirectories(resources.resolve("defaults"))
        Files.write(resources.resolve("defaults/config.yml"), "display-name: Live\n".toByteArray(StandardCharsets.UTF_8))
        URLClassLoader(arrayOf(resources.toUri().toURL()), null).use { loader ->
            for (withMigrations in listOf(false, true)) {
                val scope = ScopeImpl("strict-kotlin-install-$withMigrations")
                try {
                    val data = temporaryDirectory.resolve("data-$withMigrations")
                    if (withMigrations) {
                        ConfigModule.install(scope, data, loader, "defaults", { MigrationRunner() }, mapper)
                    } else {
                        ConfigModule.install(scope, data, loader, "defaults", mapper)
                    }
                    assertEquals("Live", scope.config(Settings::class.java, "config.yml").value().name)
                } finally {
                    scope.close()
                }
            }
        }
    }

    @Test
    fun `Kotlin aggregate copies inputs before a single candidate publication`() {
        data class Catalog(val ports: Map<String, Int>, val featured: String) {
            init { require(ports.containsKey(featured)) }
        }
        val snapshot = ConfigSnapshot(Catalog(mapOf("old" to 25565), "old"))
        val old = snapshot.value()
        val source = mutableMapOf("new" to 25566)
        val candidate = snapshot.prepare {
            Catalog(Collections.unmodifiableMap(LinkedHashMap(source)), "new")
        }
        source["new"] = 1
        assertSame(old, snapshot.value())
        snapshot.commit(candidate)
        assertEquals(25566, snapshot.value().ports["new"])
        assertEquals(25565, old.ports["old"])
        assertThrows(ConfigException::class.java) { snapshot.commit(candidate) }
    }
}
