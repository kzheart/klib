package me.kzheart.klib.compat

import java.util.Collections
import java.util.Optional

/** Closed-set discovery: relocation rewrites the class names with the bundled implementation. */
class CompatProviders private constructor() {
    companion object {
        private val implementations = Collections.unmodifiableList(listOf(
            "me.kzheart.klib.compat.v1_12.V1_12CompatImplementation",
            "me.kzheart.klib.compat.v1_20.V1_20CompatImplementation",
            "me.kzheart.klib.compat.v1_21.V1_21CompatImplementation",
            "me.kzheart.klib.compat.v26.V26CompatImplementation"))
        @JvmStatic fun discover(): List<CompatProvider> = discover(CompatProviders::class.java.classLoader)
        @JvmStatic fun discover(loader: ClassLoader): List<CompatProvider> =
            Collections.unmodifiableList(implementations.mapNotNull { instantiate(it, loader) })
        @JvmStatic fun bundledImplementationClassNames(): List<String> = implementations
        @JvmStatic fun resolver(): CompatResolver = resolver(CompatProviders::class.java.classLoader)
        @JvmStatic fun resolver(loader: ClassLoader): CompatResolver {
            val providers = discover(loader)
            check(providers.isNotEmpty()) {
                "No bundled klib compatibility implementation found on the classpath; expected at least one of $implementations" +
                    ". Add the matching klib-compat-v* module (Gradle 插件写法：klib { modules(\"compat-v1_12\", ...) })."
            }
            return CompatResolver(providers)
        }
        @JvmStatic fun resolve(serverVersion: String): CompatProvider = resolver().resolve(serverVersion)
        @JvmStatic fun detectServerVersion(): Optional<String> = try {
            val version = Class.forName("org.bukkit.Bukkit").getMethod("getBukkitVersion").invoke(null)
            Optional.ofNullable(version as? String)
        } catch (_: ReflectiveOperationException) { Optional.empty() }
        catch (_: RuntimeException) { Optional.empty() }
        @JvmStatic fun resolveCurrent(): CompatProvider = resolve(detectServerVersion().orElseThrow {
            IllegalStateException("Cannot detect the running server version; org.bukkit.Bukkit is unavailable. " +
                "Call CompatProviders.resolve(String) with an explicit version instead.")
        })
        private fun instantiate(name: String, loader: ClassLoader): CompatProvider? = try {
            Class.forName(name, true, loader).asSubclass(CompatProvider::class.java).getDeclaredConstructor().newInstance()
        } catch (_: ClassNotFoundException) { null }
        catch (_: NoClassDefFoundError) { null }
        catch (failure: ReflectiveOperationException) {
            throw IllegalStateException("Cannot instantiate bundled compatibility implementation $name", failure)
        }
    }
}
