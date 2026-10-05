# Klib

Klib 是面向 Bukkit/Paper 插件的 Java 8 模块化基础库，提供生命周期、配置、语言、命令、物品、
数据、UI、脚本、外部插件集成、版本能力查询、数据包侧边栏和远程诊断客户端。

本仓库只包含 Apache License 2.0 公共模块和最小的 `klib-guard-api` 编译契约。Gradle 构建插件由
[kzheart/klib-gradle-plugin](https://github.com/kzheart/klib-gradle-plugin) 独立维护；Guard runtime、
Native、Collector 和生产部署配置不在本仓库中。

注解命令通过 `@Command(value = "mail", help = true)` 安装自动分页帮助，按权限与玩家限制过滤路由，并使用 `@Description` 显示用途。主命令默认显示帮助、GUI 使用独立子命令的完整声明见 [Command 模块](docs/modules/command.md#自动帮助与主命令入口)。

命令在插件启动时一次声明：支持公开生命周期 API 的 Paper 使用 `LifecycleEvents.COMMANDS` 与 `BasicCommand`，其他 Bukkit/Paper 保留启动期 `CommandMap` 注册。配置重载只更新业务状态；作用域关闭立即停用执行、补全与权限检查，但物理节点可能保留到服务端生命周期重建或重启。公共制品仍保持 Java 8 边界，完整注册与冲突契约见 [Command 模块](docs/modules/command.md#启动注册配置重载与逻辑停用)。

## 最小接入

版本记录见 [CHANGELOG.md](CHANGELOG.md)，下文中的 `<...-version>` 填写 Maven Central 上已发布的版本。Java 源码与示例使用显式 import 和简单类名；嵌套类型使用 Outer.Inner。

发布和部署业务插件时，直接使用 Maven Central 的正式制品。新增库功能应先发布新版本，再更新 `libraryVersion`；业务插件的 CI 和部署构建无需本机 Klib 源码路径，也不通过 `includeBuild` 或 `mavenLocal` 替换正式依赖。

推荐通过 Klib Gradle 插件选择模块：

```kotlin
plugins {
    id("me.kzheart.klib") version "<gradle-plugin-version>"
}

klib {
    libraryVersion.set("<klib-version>")
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
    implementation("me.kzheart.klib:klib-core:<klib-version>")
    implementation("me.kzheart.klib:klib-config:<klib-version>")
}
```

公共库版本和 Guard API 版本相互独立：

```kotlin
dependencies {
    compileOnly("me.kzheart.klib:klib-guard-api:<guard-api-version>")
}
```

## 模块

| 模块 | 用途 |
| --- | --- |
| `klib-core` | `KPlugin`、`Scope`、调度、事件、资源释放、冷却与加权随机 |
| `klib-config` | YAML 配置映射、第三方格式原值树、子节文本与创建、迁移及原子重载 |
| `klib-lang` | 多语言消息、占位符、富文本与 Paper 宿主 Adventure 适配 |
| `klib-command` | 类型化命令树、Unicode 别名、权限、建议、启动期生命周期注册、按玩家过滤的客户端树与显式裸标签覆盖 |
| `klib-item` | 物品构建、标签、跨版本编解码与 ZaphkielPlus/MMOItems/NeigeItems/ItemsAdder/MythicMobs 物品适配 |
| `klib-data` | 存储契约、迁移与玩家数据缓存，不包含存储实现或第三方运行时 |
| `klib-data-json` | JSON 文件存储；使用宿主提供的 Gson |
| `klib-data-jdbc` | JDBC 公共执行引擎，不包含数据库驱动 |
| `klib-data-sqlite` | SQLite 存储；使用宿主提供的 SQLite JDBC |
| `klib-data-mysql` | MySQL 存储与 MySQL Connector/J |
| `klib-data-postgresql` | PostgreSQL 存储、pgJDBC 与事务迁移 |
| `klib-ui` | 菜单、宿主物品栏工厂、分页、投放区与聊天输入 |
| `klib-script` | Kether 嵌套动作、同步编译检查、作用域拥有的异步执行、显式旧配置 case 兼容、支持独立异步上下文安装的可选 Bukkit 侧边栏与懒加载 JavaScript 宿主、原生帧与 TabooLib/Guard 互操作；额外宿主能力显式启用 |
| `klib-hook` | Vault、PlayerPoints、XConomy、PlaceholderAPI，以及可退还的消耗与奖励 |
| `klib-compat*` | Minecraft 版本能力、实现选择与按玩家发送的数据包侧边栏 |
| `klib-remote` | 插件日志、Incident 与离线交付客户端 |
| `klib-guard-api` | 受保护商品的生命周期与门户级 Kether Broker 契约 |

完整说明见 [Wiki](https://github.com/kzheart/klib/wiki) 与 [docs/README.md](docs/README.md)。

## AI 编程助手技能

[klib 技能](skills/klib/SKILL.md) 按需读取本仓库的 [Wiki](https://github.com/kzheart/klib/wiki)，不依赖本地源码路径。

在 Codex 中可使用 `$skill-installer` 安装：

```text
$skill-installer 安装 https://github.com/kzheart/klib/tree/main/skills/klib
```

其他支持 `SKILL.md` 的工具可按各自的技能安装方式使用 `skills/klib` 目录。

## 从源码构建

构建使用 JDK 21 toolchain，但所有公共 Java 制品通过 `--release 8` 生成 Java 8 字节码：

```bash
./gradlew clean check --no-configuration-cache
```

## License

本仓库使用 [Apache License 2.0](LICENSE)。第三方归属见 [NOTICE](NOTICE) 与
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
