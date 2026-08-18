plugins {
    id("me.kzheart.klib") version "0.2.0"
}

group = "me.kzheart.klib.example"
version = "0.1.0-SNAPSHOT"
layout.buildDirectory = file("../build")

sourceSets {
    main {
        java.srcDir("../src/main/java")
        resources.srcDir("../src/main/resources")
    }
}

klib {
    name("klib-empty")
    main("me.kzheart.example.empty.EmptyPlugin")
    version(project.version.toString())
    noApiVersion() // 支持 1.12.2：不生成 api-version
    targetPackage("me.kzheart.example.empty")
    modules {
        command()
    }
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.12.2-R0.1-20180712.012057-156") {
        isTransitive = false
    }
}
