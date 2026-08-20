import java.util.zip.ZipFile

plugins {
    java
}

group = "me.kzheart.klib.example"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.encoding = "UTF-8"
}

dependencies {
    compileOnly("me.kzheart.klib:klib-guard-api:0.2.0")
    compileOnly("org.spigotmc:spigot-api:1.12.2-R0.1-20180712.012057-156") {
        isTransitive = false
    }
}

tasks.jar {
    archiveBaseName.set("remote-klib-cloud-plugin")
    manifest {
        attributes(
            "Implementation-Title" to "Remote Klib Cloud Plugin Example",
            "Implementation-Version" to project.version
        )
    }
}

val verifyCloudProductJar = tasks.register("verifyCloudProductJar") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Verifies the Guard entrypoint and dependency-free product JAR."
    dependsOn(tasks.jar)

    doLast {
        val jarFile = tasks.jar.get().archiveFile.get().asFile
        ZipFile(jarFile).use { jar ->
            val entrypoint = jar.getEntry("META-INF/klib-guard/entrypoint")
                ?: throw GradleException("Missing Guard entrypoint descriptor")
            val entrypointClass = jar.getInputStream(entrypoint).bufferedReader().use { it.readText().trim() }
            check(entrypointClass == "me.kzheart.example.cloud.RemoteCloudExample") {
                "Unexpected Guard entrypoint: $entrypointClass"
            }

            check(jar.getEntry("plugin.yml") == null) {
                "A Guard product must not be packaged as a standalone Bukkit plugin"
            }

            val forbiddenPrefixes = listOf("me/kzheart/klib/", "org/bukkit/")
            val bundledDependency = jar.entries().asSequence()
                .map { it.name }
                .firstOrNull { name -> forbiddenPrefixes.any(name::startsWith) }
            check(bundledDependency == null) {
                "Compile-only dependency was bundled: $bundledDependency"
            }
        }
    }
}

tasks.check {
    dependsOn(verifyCloudProductJar)
}
