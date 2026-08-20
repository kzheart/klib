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
            name = "spigotSnapshots"
            url = uri("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
            content {
                includeGroup("org.spigotmc")
            }
        }
        maven {
            name = "codeMc"
            url = uri("https://repo.codemc.io/repository/maven-public/")
            content {
                includeGroup("de.tr7zw")
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

include(
    "klib-core",
    "klib-command",
    "klib-config",
    "klib-lang",
    "klib-item",
    "klib-data",
    "klib-data-json",
    "klib-data-jdbc",
    "klib-data-sqlite",
    "klib-data-mysql",
    "klib-ui",
    "klib-script",
    "klib-hook",
    "klib-compat",
    "klib-compat-v1_12",
    "klib-compat-v1_20",
    "klib-compat-v1_21",
    "klib-compat-v26",
    "klib-remote",
    "klib-guard-api",
)
