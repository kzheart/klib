# Klib

Klib 是面向 Bukkit/Paper 插件的 Java 8 模块化基础库，提供生命周期、配置、语言、命令、物品、
数据、UI、脚本、外部插件集成、版本能力查询和远程诊断客户端。

本仓库只包含 Apache License 2.0 公共模块和最小的 `klib-guard-api` 编译契约。Gradle 构建插件由
[kzheart/klib-gradle-plugin](https://github.com/kzheart/klib-gradle-plugin) 独立维护；Guard runtime、
Native、Collector 和生产部署配置不在本仓库中。

## 最小接入

推荐通过 Klib Gradle 插件选择模块：

```kotlin
plugins {
    id("me.kzheart.klib") version "0.5.0"
}

klib {
    name("ExamplePlugin")
    main("com.example.plugin.ExamplePlugin")
    version(project.version.toString())
    targetPackage("com.example.plugin")
    modules {
        command()
    }
}
```

`settings.gradle.kts` 至少需要：

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
        maven("https://repo.codemc.io/repository/maven-public/")
    }
}
```

需要完全手工管理依赖时，也可以直接使用 Maven 坐标：

```kotlin
dependencies {
    implementation("me.kzheart.klib:klib-core:0.4.0")
    implementation("me.kzheart.klib:klib-config:0.4.0")
}
```

公共库版本和 Guard API 版本相互独立：

```kotlin
dependencies {
    compileOnly("me.kzheart.klib:klib-guard-api:0.2.0")
}
```

## 模块

| 模块 | 用途 |
| --- | --- |
| `klib-core` | `KPlugin`、`Scope`、调度、事件与资源释放 |
| `klib-config` | YAML 配置映射、迁移与原子重载 |
| `klib-lang` | 多语言消息、占位符和富文本 |
| `klib-command` | 类型化命令树、权限、建议与内置管理命令 |
| `klib-item` | 物品构建、标签和跨版本编解码 |
| `klib-data` | 存储契约、迁移与玩家数据缓存，不包含存储实现或第三方运行时 |
| `klib-data-json` | JSON 文件存储；使用宿主提供的 Gson |
| `klib-data-jdbc` | JDBC 公共执行引擎，不包含数据库驱动 |
| `klib-data-sqlite` | SQLite 存储；使用宿主提供的 SQLite JDBC |
| `klib-data-mysql` | MySQL 存储与 MySQL Connector/J |
| `klib-ui` | 菜单、分页、投放区与聊天输入 |
| `klib-script` | Kether 脚本、异步组合与 Guard 商品互操作适配 |
| `klib-hook` | Vault、PlayerPoints、XConomy 和 PlaceholderAPI |
| `klib-compat*` | Minecraft 版本能力与实现选择 |
| `klib-remote` | 插件日志、Incident 与离线交付客户端 |
| `klib-guard-api` | 受保护商品的生命周期与门户级 Kether Broker 契约 |

完整说明见 [docs/README.md](docs/README.md)，示例见 [examples/README.md](examples/README.md)。

## 从源码构建

构建使用 JDK 21 toolchain，但所有公共 Java 制品通过 `--release 8` 生成 Java 8 字节码：

```bash
./gradlew clean check --no-configuration-cache
```

## License

本仓库使用 [Apache License 2.0](LICENSE)。第三方归属见 [NOTICE](NOTICE) 与
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
