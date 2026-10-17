# 模块布局与依赖方向

源码按职责归组，保留按需打包的模块边界；目录分组不改变 Gradle 项目名、Maven 坐标或公共包名。

## 源码结构

```text
libraries/
  core/                 生命周期、作用域、事件、任务与协程
  command/              命令树、注解、帮助与参数处理
  config/               配置映射、迁移与重载
  lang/                 消息、占位符与文本渲染
  item/                 物品构建、原生标签与外部物品适配
  ui/                   状态会话、菜单与聊天管理面板
  hook/                 外部插件集成
storage/
  data/                 存储契约、迁移与玩家数据缓存
  jdbc/                 共享 JDBC 会话、SQL 执行器与事务
  pool/                 可选连接池薄适配，不由 JDBC/KV 后端自动引入
  json/                 JSON 文件后端
  sqlite/               SQLite 后端
  mysql/                MySQL 后端与驱动依赖
  postgresql/           PostgreSQL 后端与驱动依赖
platform/
  compat/               兼容契约、能力选择与共享侧边栏实现
  versions/
    v1_12/              旧版协议与版本入口
    v1_20/              版本范围和能力入口
    v1_21/              版本范围和能力入口
    v26/                版本范围和能力入口
runtime/
  script/               Kether 解析、动作、执行与宿主互操作
  remote/               日志、诊断队列与远程传输
  guard-api/            公开生命周期与隔离加载契约
```

每个叶模块保留自己的 `src/main/java`、`src/main/kotlin`、测试与资源目录。
构建输出在相应叶模块的 `build/` 中；`libraries` 等目录组本身不是 Gradle 项目或发布制品。

## 项目身份与构建入口

[gradle/module-layout.properties](../../gradle/module-layout.properties) 是完整的 22 项映射来源。
[settings.gradle.kts](../../settings.gradle.kts) 读取它并设置 `ProjectDescriptor.projectDir`，发布逻辑继续按原项目名设置 `artifactId`。

| 源码目录 | Gradle 项目 | Maven artifactId |
| --- | --- | --- |
| `libraries/core` 等应用模块 | `:klib-core` 等 | `klib-core` 等 |
| `storage/data` | `:klib-data` | `klib-data` |
| `storage/jdbc` 等后端 | `:klib-data-jdbc` 等 | `klib-data-jdbc` 等 |
| `platform/compat` | `:klib-compat` | `klib-compat` |
| `platform/versions/v1_20` 等入口 | `:klib-compat-v1_20` 等 | `klib-compat-v1_20` 等 |
| `runtime/script`、`runtime/remote` | `:klib-script`、`:klib-remote` | `klib-script`、`klib-remote` |
| `runtime/guard-api` | `:klib-guard-api` | `klib-guard-api` |

group 仍为 `me.kzheart.klib`，各制品的版本规则不变。消费方的 `modules {}`、Maven 依赖、`project(":klib-core")` 和 Gradle 任务路径无需修改。
例如运行 `./gradlew :klib-core:test`，报告位于 `libraries/core/build/reports/tests/test/`。

## 依赖方向

以下为当前生产项目依赖，箭头表示左侧使用右侧；外部库依赖以 [根构建配置](../../build.gradle.kts) 为准。

| 使用方 | Klib 项目依赖 | 边界 |
| --- | --- | --- |
| `core`、`item` | 无 | 不依赖版本入口、运行引擎或数据库驱动 |
| `config`、`hook` | `core` | 稳定能力消费核心生命周期 |
| `lang` | `core`；实现依赖 `config` | 配置属于文本实现细节 |
| `command` | `core`、`lang` | 不依赖 UI，配置经 Lang 运行依赖带入 |
| `ui` | `core`、`item`、`command` | UI 是组合层，不让基础能力反向依赖它 |
| `data` | `core` | 统一契约与生命周期 |
| `json`、`jdbc` | `data` | 文件后端与 JDBC 引擎各自实现契约 |
| `sqlite`、`mysql`、`postgresql` | `jdbc` | 方言与驱动差异留在具体后端 |
| `pool` | `jdbc` | 可选 DBCP 适配，仅显式选择时引入连接池；不反向依赖具体驱动 |
| `compat` | `core` 仅编译期 | 侧边栏入口使用 Scope，不发布为传递依赖 |
| `compat-v*` | `compat` | 薄版本入口使用公共兼容层 |
| `script` | `core`；`guard-api` 仅编译期 | 引擎消费核心，隔离互操作由宿主提供契约 |
| `remote` | `core` | 传输与队列使用核心服务，不反向耦合核心 |
| `guard-api` | `core` | Java 加载/扩展契约与 Kotlin 生命周期实现；内置 Kotlin/协程依赖 |

表中未标“仅编译期”或“实现依赖”的项目依赖为 `api`。测试可为装配与跨版本发现额外依赖其他实现，不作为生产依赖方向。
版本敏感代码边界由 `VersionSensitiveCodeBoundaryTest` 检查，白名单精确到受审查的共享适配文件。

## 为什么保留这些模块

- 应用能力按需组合：命令、配置、物品和 UI 的依赖不同，合成一个大 JAR 会强制带入不相关功能与库。
- 存储驱动按需选择：MySQL 和 PostgreSQL 携带各自运行驱动，JSON 与 SQLite 使用宿主提供的对应库；合并会破坏驱动隔离与可选打包。
- 存储共性集中于 `data` 和 `jdbc`：契约、事务、资源释放与迁移门禁共享，具体后端只承担真实存储差异。
- 版本入口保持独立：`compat-v*` 负责实际版本范围、能力与服务发现，使用方通过 `compat` 接入，可只打包需要的入口。
- 现代侧边栏的包结构发现、发送与计分实现集中于 `platform/compat`；现代版本入口共享它，旧版 team/prefix 协议保留独立语义。
- `script` 和 `remote` 分别承担脚本引擎与传输队列，拥有不同生命周期及依赖；不能因为同属运行时就互相强制加载。
- Guard API 保持独立发布的 Java 生命周期/加载契约与 Kotlin 生命周期实现，不引入私有 Guard runtime、Native 或协议实现；不能据此声称其整个传递依赖闭包不含 Kotlin。

目录归组与职责共享同时存在：把重复实现下沉共享层，而不以减少项目数量为由合并独立的依赖、发布或加载边界。

## 维护与迁移兼容

- IDE 和源码工具应重新导入 Gradle。直接访问旧源码位置的工具必须使用映射后的路径，不创建旧目录软链接或复制源码。
- Java/Kotlin 包名、资源相对路径、JVM 类型名与既有 Gradle/Maven 身份保持不变；单纯移动目录不改变二进制契约。
- 根 `check` 包含 `checkModuleLayout`，验证项目身份、分组目录与生产源码存在，并拒绝遗留的顶层模块目录。
- `scripts/check-java8-api.sh` 按同一映射检查叶模块 JAR；发布 staging 仓库仍按 Maven 坐标组织，不按源码目录组织。
- `scripts/check-jvm-abi.py` 读取当前映射；没有映射的历史基线使用旧 flat 布局。两者只扫描 `build/classes/java/main` 和 `build/classes/kotlin/main`，不计入测试、测试夹具或其他 source set。
- 静态回归运行 `python3 -m unittest discover -s scripts/tests -v`，覆盖完整映射、旧/新布局、测试产物排除与空基线保护；CI 与发布验证均执行它。
- 外部 QA 脚本读取相同映射；历史基线源码、ABI 快照、日志与产物摘要保留原状，新运行输出必须与历史证据区分。

移动目录后应运行 `./gradlew clean check publishToMavenLocal centralDryRunBundle --no-configuration-cache`，
再以同一干净产物完成 Java/Kotlin 消费与旧二进制链接验证。目录检查或增量编译通过不能代替最终运行验证。
