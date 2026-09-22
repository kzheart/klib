import java.util.zip.ZipFile

plugins { id("me.kzheart.klib") version "0.5.1" }
group = "me.kzheart.klib.example"
version = "1.0.0"
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
tasks.withType<JavaCompile>().configureEach { options.release.set(8) }
klib {
    name("AnnotatedKlibExample")
    main("me.kzheart.example.annotated.AnnotatedExample")
    version(project.version.toString())
    apiVersion("1.20")
    libraryVersion.set("0.5.0")
    targetPackage("me.kzheart.example.annotated")
    modules { command(); config(); ui() }
}
dependencies { compileOnly("org.spigotmc:spigot-api:1.20.6-R0.1-SNAPSHOT") }

val verifyAnnotationJar = tasks.register("verifyAnnotationJar") {
    dependsOn("shadowJar")
    doLast {
        val file = layout.buildDirectory.file("libs/annotated-klib-plugin-${project.version}-all.jar").get().asFile
        ZipFile(file).use { archive ->
            val names = archive.entries().asSequence().map { it.name }.toList()
            check("plugin.yml" in names)
            check(names.any { it.endsWith("/command/AnnotatedCommands.class") })
            check(names.any { it.endsWith("/ui/AnnotatedMenu.class") })
            check(names.none { it.startsWith("me/kzheart/klib/") || it.startsWith("org/bukkit/") })
            for (name in names.filter { it.endsWith(".class") && !it.startsWith("META-INF/versions/") }) {
                archive.getInputStream(archive.getEntry(name)).use { input ->
                    val header = ByteArray(8)
                    check(input.read(header) == 8)
                    val major = ((header[6].toInt() and 255) shl 8) or (header[7].toInt() and 255)
                    check(major <= 52) { "Non-Java-8 class: $name ($major)" }
                }
            }
        }
    }
}
tasks.named("check") { dependsOn(verifyAnnotationJar) }
