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
  <a href="https://central.sonatype.com/namespace/me.kzheart.klib"><img src="https://img.shields.io/maven-central/v/me.kzheart.klib/klib-core?style=flat-square&label=Maven%20Central&color=2563eb" alt="Maven Central 最新版本"></a>
  <img src="https://img.shields.io/badge/Java-8%2B-437291?style=flat-square" alt="Java 8 API 与字节码">
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

Klib 把插件里反复要写的基础能力拆成独立模块：命令、配置、语言、物品、菜单、数据存储、脚本和外部插件集成。按需选用，资源随插件生命周期自动释放。

```java
public final class MyPlugin extends KPlugin {
    @Override
    protected void setup() {
        CommandModule.install(this);
        commands().register(new CoinCommands());
    }
}

@Command(value = "coins", help = true)   // 自动生成分页帮助
@Permission("myplugin.coins")
public final class CoinCommands {
    @Route("give <target> <amount>")
    @Description("向玩家发放金币")
    public void give(CommandSender sender,
            @Param("target") Player target,
            @Param("amount") int amount) {
        // 参数已解析并校验，补全和权限过滤自动完成
    }
}
```

- **兼容范围**：Bukkit / Spigot / Paper；公共制品为 Java 8 字节码，实际运行所需的 Java 版本由服务端决定。
- **发布方式**：Maven Central，配套 [Gradle 插件](https://github.com/kzheart/klib-gradle-plugin) 负责选模块、生成 `plugin.yml` 和依赖重定位。

## 快速接入

把占位符换成已发布的版本（见上方徽章和 [更新日志](CHANGELOG.md)）：

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

- `modules {}` 只负责打包，代码里还要在 `KPlugin.setup()` 中安装所选模块。
- 依赖会自动补齐，例如 `command()` 会带入 Core、Config 和 Lang。

完整接入（仓库配置、服务端 API 依赖）和常用模块：

- [开始使用](https://github.com/kzheart/klib/wiki/Getting-Started)：项目配置、插件入口与模块安装。
- [构建与打包](https://github.com/kzheart/klib/wiki/Build)：仓库、依赖闭包、重定位与手工接入。
- [命令模块](docs/modules/command.md)：命令声明、帮助样式、异常策略与启动注册契约。
- [UI 模块](docs/modules/ui.md)：菜单、字段编辑、分组管理面板与聊天输入。

不用 Gradle 插件时，直接依赖 `me.kzheart.klib:klib-<module>:<klib-version>`，自己完成打包和重定位。

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
| `klib-ui` | 菜单、分页、投放区、字段与命令管理面板 | [UI](docs/modules/ui.md) |
| `klib-script` | Kether 动作、编译检查、作用域执行与可选宿主互操作 | [Script](docs/modules/script.md) |
| `klib-hook` | Vault、PlayerPoints、XConomy、PlaceholderAPI 与消耗奖励 | [Hook](docs/modules/hook.md) |
| `klib-compat` | 版本能力发现、实现选择与数据包侧边栏 | [Compat](docs/modules/compat.md) |
| `klib-remote` | 插件日志、Incident 与离线交付客户端 | [Remote](docs/modules/remote.md) |

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

> 本仓库只包含公共 Java 模块和最小 `klib-guard-api` 编译契约；Gradle 插件另有仓库，Guard runtime 等私有组件不在此处。

## 许可证

Klib 使用 [Apache License 2.0](LICENSE)。版权与第三方归属见 [NOTICE](NOTICE) 和 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
