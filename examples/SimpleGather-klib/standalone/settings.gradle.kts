pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") {
            content { includeGroup("org.spigotmc") }
        }
        maven("https://repo.codemc.io/repository/maven-public/") {
            content { includeGroup("de.tr7zw") }
        }
    }
}

rootProject.name = "SimpleGather-klib"
