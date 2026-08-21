plugins {
    id("me.kzheart.klib") version "0.5.1"
}

group = "me.kzheart.klib.example"
version = "0.1.0"

klib {
    targetPackage("me.kzheart.example.cloud")
    modules {
        none()
    }
    guardProduct {
        entrypoint("me.kzheart.example.cloud.RemoteCloudExample")
    }
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.12.2-R0.1-20180712.012057-156") {
        isTransitive = false
    }
}
