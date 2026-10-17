pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven {
            name = "minecraftLibraries"
            url = uri("https://libraries.minecraft.net")
            content { includeGroup("com.mojang") }
        }
        maven {
            name = "spigotSnapshots"
            url = uri("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
            content {
                includeGroup("org.spigotmc")
            }
        }
        maven {
            name = "extendedClip"
            url = uri("https://repo.extendedclip.com/releases/")
            content {
                includeGroup("me.clip")
            }
        }
    }
}

rootProject.name = "klib"

// Source groups are directories, not extra Gradle projects or publication coordinates.
val moduleLayout = java.util.Properties().apply {
    file("gradle/module-layout.properties").inputStream().use { load(it) }
}
moduleLayout.stringPropertyNames().sorted().forEach { moduleName ->
    include(moduleName)
    project(":$moduleName").projectDir = file(moduleLayout.getProperty(moduleName))
}
