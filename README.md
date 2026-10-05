<p align="center">
  <img src="docs/assets/klib-mark.svg" width="72" height="72" alt="Klib 标识">
</p>

<h1 align="center">Klib</h1>

<p align="center">
  面向 Bukkit、Spigot 与 Paper 插件的模块化 Java 基础库<br>
  按需组合公共能力，明确管理生命周期。
</p>

<p align="center">
  <a href="https://github.com/kzheart/klib/actions/workflows/ci.yml"><img src="https://github.com/kzheart/klib/actions/workflows/ci.yml/badge.svg?branch=main" alt="持续集成状态"></a>
  <a href="https://central.sonatype.com/namespace/me.kzheart.klib"><img src="https://img.shields.io/badge/Maven_Central-published-2563eb?style=flat-square" alt="Maven Central 制品"></a>
  <a href="docs/modules/core.md"><img src="https://img.shields.io/badge/Java_API-8-437291?style=flat-square" alt="Java 8 API 与字节码"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache_2.0-64748b?style=flat-square" alt="Apache License 2.0"></a>
</p>

<p align="center">
  <a href="https://github.com/kzheart/klib/wiki">使用文档</a> ·
  <a href="#快速接入">快速接入</a> ·
  <a href="#模块索引">模块索引</a> ·
  <a href="https://github.com/kzheart/klib-gradle-plugin">Gradle 插件</a> ·
  <a href="CHANGELOG.md">更新日志</a>
</p>

---

Klib 将插件中反复使用的基础能力组织为独立模块：从 `KPlugin` 与 `Scope` 出发，按需接入命令、配置、物品、界面、数据存储和脚本。业务插件显式安装模块、持有资源，并在作用域结束时统一释放。

模块通过 Maven Central 发布，配套的 [Klib Gradle 插件](https://github.com/kzheart/klib-gradle-plugin) 负责模块选择、`plugin.yml` 生成与依赖重定位。公共 Java 制品保持 Java 8 API 与字节码边界；实际运行所需的 Java 版本由目标服务端决定。

## 核心能力

| 开发基础 | 交互与集成 |
| :--- | :--- |
| **生命周期与资源管理**<br>`KPlugin`、`Scope`、组件、事件与调度，统一持有和释放插件资源。 | **界面与聊天交互**<br>菜单、分页、投放区、聊天控制面板与输入，组合适合业务的操作流程。 |
| **命令与配置**<br>类型化命令、注解路由、可定制的交互式帮助，以及配置映射、迁移和重载。 | **物品与平台适配**<br>物品构建、标签、编解码、外部物品来源，以及显式选择的版本能力实现。 |
| **数据与脚本**<br>JSON、SQLite、MySQL、PostgreSQL 存储，以及 Kether 动作与可选宿主互操作。 | **插件集成与诊断**<br>经济、占位符、消耗与奖励接口，以及日志、Incident 和离线交付客户端。 |

## 快速接入

推荐使用 Gradle 插件选取模块。构建插件与库独立发布，请将占位符替换为各自已发布的版本；库版本记录见 [更新日志](CHANGELOG.md)。

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
        ui()
    }
}
```

这个片段展示模块选择与打包配置。完整接入还需配置仓库、目标服务端 API 的 `compileOnly` 依赖，并在 `KPlugin.setup()` 中安装所选模块。`command()` 自动补齐 Core、Config 与 Lang；`ui()` 自动补齐其依赖，运行时安装顺序见模块文档。

- [开始使用](https://github.com/kzheart/klib/wiki/Getting-Started)：项目配置、插件入口与模块安装。
- [构建与打包](https://github.com/kzheart/klib/wiki/Build)：仓库、依赖闭包、重定位与手工接入。
- [命令模块](docs/modules/command.md)：命令声明、帮助样式、异常策略与启动注册契约。
- [UI 模块](docs/modules/ui.md)：菜单、聊天面板与聊天输入。

需要手工管理依赖时，可使用 `me.kzheart.klib:klib-<module>:<klib-version>` 坐标，并自行完成打包与重定位。业务插件应依赖正式发布的制品。Guard API 使用独立版本与 `compileOnly` 接入，见 [Guard API 文档](docs/modules/guard-api.md)。

## 模块索引

按功能选择模块；数据后端和版本适配实现需要单独选择，完整依赖关系见 [构建文档](https://github.com/kzheart/klib/wiki/Build)。

| 模块 | 主要能力 | 文档 |
| :--- | :--- | :---: |
| `klib-core` | 插件与作用域、组件、事件、调度、冷却与加权随机 | [Core](docs/modules/core.md) |
| `klib-config` | YAML 映射、原值树、迁移与原子重载 | [Config](docs/modules/config.md) |
| `klib-lang` | 多语言、占位符、富文本与宿主 Adventure 适配 | [Lang](docs/modules/lang.md) |
| `klib-command` | 命令树、注解、权限、建议、帮助样式与业务异常策略 | [Command](docs/modules/command.md) |
| `klib-item` | 物品构建、标签、编解码与外部物品适配 | [Item](docs/modules/item.md) |
| `klib-data` | 存储契约、迁移与玩家缓存 | [Data](docs/modules/data.md) |
| `klib-ui` | 菜单、分页、投放区、聊天面板与输入 | [UI](docs/modules/ui.md) |
| `klib-script` | Kether 动作、编译检查、作用域执行与可选宿主互操作 | [Script](docs/modules/script.md) |
| `klib-hook` | Vault、PlayerPoints、XConomy、PlaceholderAPI 与消耗奖励 | [Hook](docs/modules/hook.md) |
| `klib-compat` | 版本能力发现、实现选择与数据包侧边栏 | [Compat](docs/modules/compat.md) |
| `klib-remote` | 插件日志、Incident 与离线交付客户端 | [Remote](docs/modules/remote.md) |
| `klib-guard-api` | 受保护商品生命周期与门户级 Kether Broker 编译契约 | [Guard API](docs/modules/guard-api.md) |

<details>
<summary><strong>数据后端与驱动</strong></summary>

`klib-data` 提供公共契约；根据实际存储方式选择实现。

| 模块 | 存储与运行时依赖 |
| :--- | :--- |
| `klib-data-json` | JSON 文件；使用宿主提供的 Gson |
| `klib-data-jdbc` | JDBC 公共执行引擎；不包含数据库驱动 |
| `klib-data-sqlite` | SQLite；使用宿主提供的 SQLite JDBC |
| `klib-data-mysql` | MySQL；包含 MySQL Connector/J |
| `klib-data-postgresql` | PostgreSQL；包含 pgJDBC，支持事务迁移 |

存储配置与使用方式见 [Data 文档](docs/modules/data.md)。

</details>

## 文档与支持

| 入口 | 内容 |
| :--- | :--- |
| [GitHub Wiki](https://github.com/kzheart/klib/wiki) | 按模块组织的接入指南与 API 用法 |
| [仓库文档](docs/README.md) | 与源码一起维护的模块边界、生命周期与线程约定 |
| [组件与注解](docs/annotations.md) | 声明组件、命令及其他注解入口 |
| [故障排查](docs/troubleshooting.md) | 常见问题与定位方式 |
| [Issues](https://github.com/kzheart/klib/issues) | 问题反馈与功能建议 |

<details>
<summary><strong>AI 编程助手接入</strong></summary>

仓库提供 [Klib 技能](skills/klib/SKILL.md)，按需读取官方 Wiki。Codex 中可通过 `$skill-installer` 安装：

```text
$skill-installer 安装 https://github.com/kzheart/klib/tree/main/skills/klib
```

其他支持 `SKILL.md` 的工具可按其安装方式使用 `skills/klib` 目录。

</details>

## 开发与贡献

从源码构建使用 JDK 21 toolchain，公共 Java 制品通过 `--release 8` 编译：

```bash
./gradlew clean check --no-configuration-cache
```

欢迎通过 [Issues](https://github.com/kzheart/klib/issues) 或 [Pull Requests](https://github.com/kzheart/klib/pulls) 反馈问题与提交改进。变更应保持公共 Java 8 边界，并同步相关模块文档；参与前请阅读 [仓库协作约定](AGENTS.md)。

本仓库维护公共 Java 模块及最小 `klib-guard-api` 编译契约。Gradle 插件独立维护；Guard runtime、Native、Collector 与生产部署配置属于私有仓库。

## 许可证

Klib 使用 [Apache License 2.0](LICENSE)。版权与第三方归属见 [NOTICE](NOTICE) 和 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
