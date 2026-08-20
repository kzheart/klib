abstract class CheckGuardApiBoundary : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val sourceRoot: DirectoryProperty

    @TaskAction
    fun verify() {
        val expected = setOf(
            "me/kzheart/klib/guard/KlibCloudPlugin.java",
            "me/kzheart/klib/guard/PluginHost.java",
            "me/kzheart/klib/guard/RemotePluginEntrypoint.java",
            "me/kzheart/klib/guard/kether/KetherInteropBroker.java",
            "me/kzheart/klib/guard/kether/KetherInteropEndpoint.java",
            "me/kzheart/klib/guard/kether/KetherInteropPeer.java",
            "me/kzheart/klib/guard/kether/KetherInteropProtocol.java",
            "me/kzheart/klib/guard/kether/KetherInteropRegistration.java",
            "me/kzheart/klib/guard/kether/KetherInteropResult.java",
        )
        val root = sourceRoot.get().asFile
        val actual = sources.files.map {
            it.relativeTo(root).invariantSeparatorsPath
        }.toSet()
        if (actual != expected) {
            throw GradleException(
                "Guard API source boundary changed: expected=$expected, actual=$actual")
        }
    }
}

plugins {
    `java-library`
}

dependencies {
    api(project(":klib-core"))
    compileOnly(libs.spigot.api) {
        isTransitive = false
    }
}

val guardApiSources = fileTree("src/main/java") {
    include("**/*.java")
}

val checkGuardApiBoundary = tasks.register<CheckGuardApiBoundary>("checkGuardApiBoundary") {
    group = "verification"
    description = "Ensures the published Guard API contains only its reviewed public boundary."
    sources.from(guardApiSources)
    sourceRoot.set(layout.projectDirectory.dir("src/main/java"))
}

tasks.named("check") {
    dependsOn(checkGuardApiBoundary)
}
