import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.gradle.plugins.signing.SigningExtension
import java.security.MessageDigest
import java.util.zip.ZipFile

abstract class CheckJava8RuntimeDependencies : DefaultTask() {
    @get:Classpath
    abstract val runtimeJars: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        var classCount = 0
        runtimeJars.files.filter { it.extension == "jar" }.forEach { jar ->
            ZipFile(jar).use { archive ->
                val entries = archive.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !entry.name.endsWith(".class") ||
                        entry.name.startsWith("META-INF/versions/") ||
                        entry.name.endsWith("module-info.class")) {
                        continue
                    }
                    classCount++
                    val header = ByteArray(8)
                    archive.getInputStream(entry).use { input ->
                        var offset = 0
                        while (offset < header.size) {
                            val read = input.read(header, offset, header.size - offset)
                            if (read < 0) {
                                throw GradleException(
                                    "Truncated class file: ${jar.name}!/${entry.name}")
                            }
                            offset += read
                        }
                    }
                    val major = (header[6].toInt() and 0xff) shl 8 or
                        (header[7].toInt() and 0xff)
                    if (major > 52) {
                        throw GradleException(
                            "Runtime dependency requires classfile major $major (> Java 8): " +
                                "${jar.name}!/${entry.name}")
                    }
                }
            }
        }
        logger.lifecycle("Java 8 runtime dependency check passed for $classCount class files.")
    }
}

plugins {
    base
}

group = "me.kzheart.klib"

val semanticVersion = Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)")
fun requiredVersionProperty(name: String): String {
    val value = providers.gradleProperty(name).orNull?.trim().orEmpty()
    if (!semanticVersion.matches(value)) {
        throw GradleException("$name must be a three-part semantic version")
    }
    return value
}

val klibVersion = requiredVersionProperty("klibVersion")
val klibGuardApiVersion = requiredVersionProperty("klibGuardApiVersion")
version = klibVersion

val klibLibraryProjectPaths = listOf(
    ":klib-core",
    ":klib-command",
    ":klib-config",
    ":klib-lang",
    ":klib-item",
    ":klib-data",
    ":klib-ui",
    ":klib-script",
    ":klib-hook",
    ":klib-compat",
    ":klib-compat-v1_12",
    ":klib-compat-v1_20",
    ":klib-compat-v1_21",
    ":klib-compat-v26",
    ":klib-remote",
)
val javaProjectPaths = klibLibraryProjectPaths + ":klib-guard-api"
val javaProjects = javaProjectPaths.map(::project)
val publishableProjects = javaProjects

val junitBom = libs.junit.bom
val junitJupiter = libs.junit.jupiter
val junitLauncher = libs.junit.platform.launcher
val spigotApi = libs.spigot.api
val snakeYaml = libs.snakeyaml
val adventureMiniMessage = libs.adventure.minimessage
val itemNbtApi = libs.item.nbt.api
val placeholderApi = libs.placeholderapi
val sqliteJdbc = libs.sqlite.jdbc
val mysqlConnector = libs.mysql.connector
val h2 = libs.h2
val gson = libs.gson

val publicationUrl = "https://github.com/kzheart/klib"
val privateMavenUrl = providers.environmentVariable("KLIB_MAVEN_REPOSITORY_URL")
val privateMavenUsername = providers.environmentVariable("KLIB_MAVEN_REPOSITORY_USERNAME")
val privateMavenPassword = providers.environmentVariable("KLIB_MAVEN_REPOSITORY_PASSWORD")
val signingKey = providers.gradleProperty("signingKey")
    .orElse(providers.environmentVariable("MAVEN_SIGNING_KEY"))
val signingPassword = providers.gradleProperty("signingPassword")
    .orElse(providers.environmentVariable("MAVEN_SIGNING_PASSWORD"))

fun publicationDescription(projectName: String): String = when (projectName) {
    "klib-guard-api" -> "Compile-time lifecycle API for products loaded by KlibGuard."
    else -> "Klib ${projectName.removePrefix("klib-")} module for Bukkit and Paper plugins."
}

fun publicationDisplayName(projectName: String): String = when (projectName) {
    "klib-guard-api" -> "Klib Guard API"
    else -> "Klib " + projectName.removePrefix("klib-")
        .split('-')
        .joinToString(" ") { part -> part.replaceFirstChar(Char::uppercaseChar) }
}

configure(javaProjects) {
    apply(plugin = "java-library")
    group = rootProject.group
    version = if (path == ":klib-guard-api") klibGuardApiVersion else klibVersion

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
        withSourcesJar()
        withJavadocJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(8)
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-options", "-Werror"))
        doFirst {
            if (options.release.orNull != 8) {
                throw GradleException("$path must compile with --release 8")
            }
        }
    }

    tasks.withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
        onlyIf("contains documentable Java types") {
            source.files.any { it.name !in setOf("package-info.java", "BuildMarker.java") }
        }
    }

    dependencies {
        add("testImplementation", platform(junitBom))
        add("testImplementation", junitJupiter)
        add("testRuntimeOnly", junitLauncher)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
            exceptionFormat = TestExceptionFormat.FULL
        }
    }

    tasks.register<CheckJava8RuntimeDependencies>("checkJava8RuntimeDependencies") {
        group = "verification"
        description = "Rejects runtime dependency class files newer than Java 8."
        runtimeJars.from(configurations.getByName("runtimeClasspath"))
    }
}

configure(publishableProjects) {
    apply(plugin = "maven-publish")
    apply(plugin = "signing")
    val moduleName = name

    tasks.withType<Jar>().configureEach {
        from(rootProject.file("LICENSE")) {
            into("META-INF")
        }
        from(rootProject.file("NOTICE")) {
            into("META-INF")
        }
    }

    extensions.configure<PublishingExtension> {
        repositories {
            maven {
                name = "staging"
                url = rootProject.layout.buildDirectory
                    .dir("repository-staging")
                    .get()
                    .asFile
                    .toURI()
            }
            if (privateMavenUrl.isPresent) {
                maven {
                    name = "klibPrivate"
                    url = uri(privateMavenUrl.get())
                    if (privateMavenUsername.isPresent && privateMavenPassword.isPresent) {
                        credentials {
                            username = privateMavenUsername.get()
                            password = privateMavenPassword.get()
                        }
                    }
                }
            }
        }
        publications.create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = moduleName
            pom {
                name.set(publicationDisplayName(moduleName))
                description.set(publicationDescription(moduleName))
                url.set(publicationUrl)
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("kzheart")
                        name.set("kzheart")
                        email.set("kzheartgyf@gmail.com")
                    }
                }
                scm {
                    connection.set("scm:git:$publicationUrl.git")
                    developerConnection.set("scm:git:$publicationUrl.git")
                    url.set(publicationUrl)
                }
            }
        }
    }

    val publications = extensions.getByType<PublishingExtension>().publications
    extensions.configure<SigningExtension> {
        isRequired = signingKey.isPresent
        if (signingKey.isPresent) {
            useInMemoryPgpKeys(signingKey.get(), signingPassword.orNull)
        }
        sign(publications)
    }
}

project(":klib-core") {
    dependencies {
        val compileSpigot = create(spigotApi.get()) as ModuleDependency
        val testSpigot = create(spigotApi.get()) as ModuleDependency
        compileSpigot.isTransitive = false
        testSpigot.isTransitive = false
        add("compileOnly", compileSpigot)
        add("testImplementation", testSpigot)
    }
}

project(":klib-compat-v1_12") { dependencies { add("api", project(":klib-compat")) } }
project(":klib-compat-v1_20") { dependencies { add("api", project(":klib-compat")) } }
project(":klib-compat-v1_21") { dependencies { add("api", project(":klib-compat")) } }
project(":klib-compat-v26") {
    dependencies {
        add("api", project(":klib-compat"))
        add("testImplementation", project(":klib-compat-v1_12"))
        add("testImplementation", project(":klib-compat-v1_20"))
        add("testImplementation", project(":klib-compat-v1_21"))
    }
}

project(":klib-config") {
    dependencies {
        add("api", project(":klib-core"))
        add("implementation", snakeYaml)
    }
}

project(":klib-lang") {
    dependencies {
        add("api", project(":klib-core"))
        add("implementation", project(":klib-config"))
        add("implementation", adventureMiniMessage)
        val compileSpigot = create(spigotApi.get()) as ModuleDependency
        val testSpigot = create(spigotApi.get()) as ModuleDependency
        compileSpigot.isTransitive = false
        testSpigot.isTransitive = false
        add("compileOnly", compileSpigot)
        add("testImplementation", testSpigot)
    }
}

project(":klib-command") {
    dependencies {
        add("api", project(":klib-core"))
        add("api", project(":klib-lang"))
        add("testImplementation", project(":klib-config"))
        val compileSpigot = create(spigotApi.get()) as ModuleDependency
        val testSpigot = create(spigotApi.get()) as ModuleDependency
        compileSpigot.isTransitive = false
        testSpigot.isTransitive = false
        add("compileOnly", compileSpigot)
        add("testImplementation", testSpigot)
    }
}

project(":klib-script") { dependencies { add("api", project(":klib-core")) } }

project(":klib-hook") {
    dependencies {
        add("api", project(":klib-core"))
        val compileSpigot = create(spigotApi.get()) as ModuleDependency
        val testSpigot = create(spigotApi.get()) as ModuleDependency
        val testPlaceholderApi = create(placeholderApi.get()) as ModuleDependency
        compileSpigot.isTransitive = false
        testSpigot.isTransitive = false
        testPlaceholderApi.isTransitive = false
        add("compileOnly", compileSpigot)
        add("compileOnly", placeholderApi)
        add("testImplementation", testSpigot)
        add("testImplementation", testPlaceholderApi)
        add("testImplementation", snakeYaml)
        add("testImplementation", "com.google.guava:guava:21.0")
    }
}

project(":klib-item") {
    dependencies {
        val compileSpigot = create(spigotApi.get()) as ModuleDependency
        val testSpigot = create(spigotApi.get()) as ModuleDependency
        compileSpigot.isTransitive = false
        testSpigot.isTransitive = false
        add("compileOnlyApi", compileSpigot)
        add("testImplementation", testSpigot)
        add("testImplementation", "com.google.guava:guava:21.0")
        add("runtimeOnly", itemNbtApi)
    }
}

project(":klib-ui") {
    dependencies {
        add("api", project(":klib-core"))
        add("api", project(":klib-item"))
        val compileSpigot = create(spigotApi.get()) as ModuleDependency
        val testSpigot = create(spigotApi.get()) as ModuleDependency
        compileSpigot.isTransitive = false
        testSpigot.isTransitive = false
        add("compileOnlyApi", compileSpigot)
        add("testImplementation", testSpigot)
        add("testImplementation", "com.google.guava:guava:21.0")
    }
}

project(":klib-data") {
    dependencies {
        add("api", project(":klib-core"))
        add("implementation", gson)
        add("runtimeOnly", sqliteJdbc)
        add("runtimeOnly", mysqlConnector)
        add("testImplementation", sqliteJdbc)
        add("testImplementation", h2)
    }
}

project(":klib-remote") {
    dependencies {
        add("api", project(":klib-core"))
    }
    tasks.withType<Test>().configureEach {
        systemProperty(
            "klib.remote.samplingVectors",
            layout.projectDirectory.file(
                "src/test/resources/me/kzheart/klib/remote/sampling-v1.tsv").asFile.absolutePath,
        )
    }
}

tasks.named("clean") {
    dependsOn(javaProjects.map { it.tasks.named("clean") })
}

tasks.named("check") {
    dependsOn(javaProjects.map { it.tasks.named("check") })
    dependsOn("checkJava8Compatibility", "checkLicenses", "checkPublicBoundary")
}

tasks.register("checkLicenses") {
    group = "verification"
    description = "Verifies the Apache license and bundled third-party attribution."
    inputs.files("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md")
    doLast {
        val license = rootProject.file("LICENSE").readText()
        if (!license.contains("Apache License") ||
            !license.contains("Version 2.0, January 2004")) {
            throw GradleException("LICENSE must contain the complete Apache License 2.0")
        }
        val notice = rootProject.file("NOTICE").readText()
        if (!notice.contains("Copyright 2026 kzheart") ||
            !notice.contains("THIRD_PARTY_NOTICES.md")) {
            throw GradleException("NOTICE is missing required attribution")
        }
        val thirdParty = rootProject.file("THIRD_PARTY_NOTICES.md").readText()
        listOf(
            "c27e822fb34eebd7433a94efbfac0a26943cccd6",
            "Copyright (c) 2018 Bkm016",
            "Permission is hereby granted, free of charge",
        ).forEach { required ->
            if (!thirdParty.contains(required)) {
                throw GradleException("THIRD_PARTY_NOTICES.md is missing $required")
            }
        }
    }
}

tasks.register("checkPublicBoundary") {
    group = "verification"
    description = "Rejects private Guard, Collector, deployment, and Gradle-plugin sources."
    doLast {
        val forbiddenRoots = listOf("collector", "deploy", "klib-gradle-plugin")
        forbiddenRoots.forEach { name ->
            if (rootProject.file(name).exists()) {
                throw GradleException("Private or separately maintained path is present: $name")
            }
        }
        val guardRoot = rootProject.file("klib-guard")
        if (guardRoot.exists()) {
            throw GradleException("Guard runtime/native source must not be in the public repository")
        }
    }
}

tasks.register("checkJava8Artifacts") {
    group = "verification"
    description = "Scans public source and JARs for the Java 8 boundary."
    dependsOn(publishableProjects.map { it.tasks.named("jar") })
    doLast {
        val forbiddenApi = Regex(
            """\b(Map|List|Set)\.of\s*\(|java\.net\.http|Files\.(readString|writeString)\s*\(|Optional\.isEmpty\s*\(|String\.(strip|isBlank|lines|repeat)\s*\(""")
        publishableProjects.forEach { module ->
            module.fileTree("src/main/java") { include("**/*.java") }.files.forEach { source ->
                if (forbiddenApi.containsMatchIn(source.readText())) {
                    throw GradleException("Java 9+ API reference found in ${source.relativeTo(rootDir)}")
                }
            }
            val artifact = module.layout.buildDirectory.file(
                "libs/${module.name}-${module.version}.jar").get().asFile
            ZipFile(artifact).use { archive ->
                val entries = archive.entries()
                var classCount = 0
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !entry.name.endsWith(".class")) continue
                    classCount++
                    val header = ByteArray(8)
                    archive.getInputStream(entry).use { input ->
                        var offset = 0
                        while (offset < header.size) {
                            val read = input.read(header, offset, header.size - offset)
                            if (read < 0) throw GradleException("Truncated class: ${entry.name}")
                            offset += read
                        }
                    }
                    val major = (header[6].toInt() and 0xff) shl 8 or
                        (header[7].toInt() and 0xff)
                    if (major != 52) {
                        throw GradleException(
                            "Expected Java 8 classfile major 52, got $major: " +
                                "${artifact.name}!/${entry.name}")
                    }
                }
                if (classCount == 0) throw GradleException("No classes in ${artifact.name}")
            }
        }
    }
}

tasks.register("checkJava8RuntimeDependencies") {
    group = "verification"
    description = "Checks every public module's runtime dependency bytecode."
    dependsOn(javaProjects.map { it.tasks.named("checkJava8RuntimeDependencies") })
}

tasks.register("checkJava8Compatibility") {
    group = "verification"
    description = "Enforces Java 8 source, bytecode, and runtime dependency boundaries."
    dependsOn("checkJava8Artifacts", "checkJava8RuntimeDependencies")
}

tasks.register("publishToMavenLocal") {
    group = "publishing"
    description = "Publishes all public Klib modules to Maven Local."
    dependsOn(publishableProjects.map { it.tasks.named("publishToMavenLocal") })
}

val cleanMavenStaging = tasks.register<Delete>("cleanMavenStaging") {
    delete(layout.buildDirectory.dir("repository-staging"))
}
val stagingPublicationTasks = publishableProjects.map {
    it.tasks.named("publishAllPublicationsToStagingRepository")
}
publishableProjects.forEach { module ->
    module.tasks.withType<PublishToMavenRepository>().configureEach {
        if (name.endsWith("ToStagingRepository")) {
            dependsOn(cleanMavenStaging)
        }
    }
}
val stageMavenPublications = tasks.register("stageMavenPublications") {
    group = "publishing"
    description = "Stages all Maven Central candidate artifacts in repository layout."
    dependsOn(cleanMavenStaging)
    dependsOn(stagingPublicationTasks)
}

tasks.register("verifyMavenStaging") {
    group = "verification"
    description = "Verifies staged POMs, source/Javadoc JARs, and license files."
    dependsOn(stageMavenPublications)
    doLast {
        publishableProjects.forEach { module ->
            val moduleVersion = module.version.toString()
            val directory = layout.buildDirectory.dir(
                "repository-staging/me/kzheart/klib/${module.name}/$moduleVersion")
                .get().asFile
            val baseName = "${module.name}-$moduleVersion"
            listOf(
                directory.resolve("$baseName.pom"),
                directory.resolve("$baseName.jar"),
                directory.resolve("$baseName-sources.jar"),
                directory.resolve("$baseName-javadoc.jar"),
            ).forEach { artifact ->
                if (!artifact.isFile) throw GradleException("Missing staged artifact: $artifact")
            }
            val pom = directory.resolve("$baseName.pom").readText()
            listOf("<name>", "<description>", "<url>", "<licenses>", "<developers>", "<scm>")
                .forEach { element ->
                    if (!pom.contains(element)) {
                        throw GradleException("$baseName.pom lacks $element")
                    }
                }
            listOf(
                directory.resolve("$baseName.jar"),
                directory.resolve("$baseName-sources.jar"),
                directory.resolve("$baseName-javadoc.jar"),
            ).forEach { jar ->
                ZipFile(jar).use { archive ->
                    listOf("META-INF/LICENSE", "META-INF/NOTICE").forEach { entry ->
                        if (archive.getEntry(entry) == null) {
                            throw GradleException("${jar.name} lacks $entry")
                        }
                    }
                }
            }
        }
    }
}

val verifyCentralChecksums = tasks.register("verifyCentralChecksums") {
    group = "verification"
    description = "Verifies Maven Central MD5 and SHA-1 sidecars generated by Maven Publish."
    dependsOn("verifyMavenStaging")
    doLast {
        val stagingRoot = layout.buildDirectory.dir("repository-staging").get().asFile
        stagingRoot.walkTopDown()
            .filter { it.isFile && it.extension in setOf("jar", "pom", "asc") }
            .forEach { artifact ->
                listOf("MD5" to "md5", "SHA-1" to "sha1").forEach { (algorithm, suffix) ->
                    val digest = MessageDigest.getInstance(algorithm)
                    artifact.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                        }
                    }
                    val expected = digest.digest().joinToString("") { byte ->
                        "%02x".format(byte.toInt() and 0xff)
                    }
                    val sidecar = artifact.resolveSibling("${artifact.name}.$suffix")
                    if (!sidecar.isFile || sidecar.readText().trim().lowercase() != expected) {
                        throw GradleException(
                            "Missing or invalid $suffix checksum: ${sidecar.relativeTo(stagingRoot)}")
                    }
                }
            }
    }
}

tasks.register<Zip>("centralDryRunBundle") {
    group = "verification"
    description = "Builds an unsigned local-only Maven Central candidate bundle."
    dependsOn(verifyCentralChecksums)
    from(layout.buildDirectory.dir("repository-staging")) {
        include("**/*.jar", "**/*.pom")
        include("**/*.jar.md5", "**/*.jar.sha1")
        include("**/*.pom.md5", "**/*.pom.sha1")
    }
    destinationDirectory.set(layout.buildDirectory.dir("distributions/central-dry-run"))
    archiveFileName.set("klib-$klibVersion-unsigned.zip")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.register<Zip>("prepareCentralBundle") {
    group = "publishing"
    description = "Builds the signed Maven Central upload bundle."
    dependsOn(verifyCentralChecksums)
    doFirst {
        if (!signingKey.isPresent || !signingPassword.isPresent) {
            throw GradleException("MAVEN_SIGNING_KEY and MAVEN_SIGNING_PASSWORD are required")
        }
        val stagingRoot = layout.buildDirectory.dir("repository-staging").get().asFile
        val unsigned = stagingRoot.walkTopDown()
            .filter { it.isFile && it.extension in setOf("jar", "pom") }
            .firstOrNull { !it.resolveSibling("${it.name}.asc").isFile }
        if (unsigned != null) {
            throw GradleException("Missing PGP signature for ${unsigned.relativeTo(stagingRoot)}")
        }
    }
    from(layout.buildDirectory.dir("repository-staging")) {
        include("**/*.jar", "**/*.pom", "**/*.jar.asc", "**/*.pom.asc")
        include("**/*.jar.md5", "**/*.jar.sha1")
        include("**/*.pom.md5", "**/*.pom.sha1")
    }
    destinationDirectory.set(layout.buildDirectory.dir("distributions/central"))
    archiveFileName.set("klib-$klibVersion.zip")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val privatePublicationTasks = if (privateMavenUrl.isPresent) {
    publishableProjects.map { it.tasks.named("publishAllPublicationsToKlibPrivateRepository") }
} else {
    emptyList()
}
tasks.register("publishAllToPrivateMaven") {
    group = "publishing"
    description = "Publishes every public module to the configured authenticated Maven repository."
    dependsOn(privatePublicationTasks)
    doFirst {
        if (!privateMavenUrl.isPresent || !privateMavenUsername.isPresent ||
            !privateMavenPassword.isPresent) {
            throw GradleException(
                "KLIB_MAVEN_REPOSITORY_URL, KLIB_MAVEN_REPOSITORY_USERNAME, and " +
                    "KLIB_MAVEN_REPOSITORY_PASSWORD are required")
        }
    }
}

publishableProjects.forEach { module ->
    module.tasks.withType<PublishToMavenRepository>().configureEach {
        if (name.endsWith("ToKlibPrivateRepository")) {
            dependsOn(rootProject.tasks.named("check"))
        }
    }
}
