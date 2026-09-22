pluginManagement { repositories { gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
        maven("https://repo.codemc.io/repository/maven-public/")
    }
}
rootProject.name = "annotated-klib-plugin"
// Explicit source verification; omitted when consuming a published library release.
if (providers.gradleProperty("klibSource").isPresent) { includeBuild("../..") }
