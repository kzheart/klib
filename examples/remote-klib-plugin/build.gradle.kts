import java.util.zip.ZipFile

plugins {
    id("me.kzheart.klib") version "0.4.0"
}

group = "me.kzheart.klib.example"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

klib {
    name("RemoteKlibExample")
    main("me.kzheart.example.remote.RemoteKlibExamplePlugin")
    version(project.version.toString())
    apiVersion("1.20")
    targetPackage("me.kzheart.example.remote")
    modules {
        command()
    }
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.20.6-R0.1-SNAPSHOT")
}

val verifyPluginJar = tasks.register("verifyPluginJar") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Verifies the generated Bukkit metadata and relocated Klib classes."
    dependsOn(tasks.named("shadowJar"))

    doLast {
        val jarFile = layout.buildDirectory.file(
            "libs/remote-klib-plugin-${project.version}-all.jar"
        ).get().asFile
        ZipFile(jarFile).use { jar ->
            val pluginYaml = jar.getEntry("plugin.yml")
                ?: throw GradleException("Missing generated plugin.yml")
            val metadata = jar.getInputStream(pluginYaml).bufferedReader().use { it.readText() }
            check(metadata.contains("main: 'me.kzheart.example.remote.RemoteKlibExamplePlugin'")) {
                "Unexpected Bukkit main class"
            }
            check(jar.getEntry("me/kzheart/example/remote/RemoteKlibExamplePlugin.class") != null) {
                "Missing example plugin class"
            }
            check(jar.getEntry("META-INF/klib-guard/entrypoint") == null) {
                "A normal Bukkit plugin must not declare a Guard entrypoint"
            }

            val entries = jar.entries().asSequence().map { it.name }.toList()
            check(entries.any { it.startsWith("me/kzheart/example/remote/libs/klib/") }) {
                "Relocated Klib classes are missing"
            }
            check(entries.none { it.startsWith("me/kzheart/klib/") }) {
                "Unrelocated Klib classes were packaged"
            }
        }
    }
}

tasks.check {
    dependsOn(verifyPluginJar)
}
