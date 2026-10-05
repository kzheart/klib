# 故障排查

按「症状 / 原因 / 修复」收录 Klib 插件开发中最常见的构建和启动问题。

| 阶段 | 症状 |
| --- | --- |
| 构建 | [找不到 `item-nbt-api`](#could-not-find-detr7zwitem-nbt-api) · [找不到 Klib 制品或插件](#could-not-find-mekzheartklibklib--或插件-id-mekzheartklib-无法解析) · [`klib.main is not set`](#klibmain-is-not-set) · [模块方法无法解析](#模块方法无法解析) |
| 启动 / 运行 | [`NoClassDefFoundError`](#服务器启动报-noclassdeffounderror-mekzheartklib) · [1.12.2 加载失败](#插件在-1122-上加载失败或产生-api-version-告警) · [另一个 KPlugin 实例](#控制台出现检测到另一个已激活的-kplugin-实例) · [重载后命令失效](#改完配置执行重载后插件的命令不再响应) · [异步线程调用 Bukkit API](#异步线程里调用-bukkit-api-抛异常或导致服务器不稳定) · [显示键名或回退 `zh_CN`](#消息显示成键名或语言意外回退到-zh_cn) |

## 构建期

### `Could not find de.tr7zw:item-nbt-api`

- **症状**：选了 `item` 或 `ui` 模块后，依赖解析失败于 `de.tr7zw:item-nbt-api`。
- **原因**：`klib-item` 的运行时依赖只发布在 CodeMC 仓库，`klib-ui` 又依赖 `klib-item`；使用方仓库列表里没有 CodeMC。
- **修复**：在 `settings.gradle.kts` 的 `dependencyResolutionManagement.repositories` 加入 `maven("https://repo.codemc.io/repository/maven-public/")`。见 [Klib Gradle 插件仓库](https://github.com/kzheart/klib-gradle-plugin)。

### `Could not find me.kzheart.klib:klib-*` 或插件 ID `me.kzheart.klib` 无法解析

- **症状**：`id("me.kzheart.klib") version "..."` 或 `me.kzheart.klib:klib-core:...` 找不到。
- **原因**：
  - `pluginManagement.repositories` 或 `dependencyResolutionManagement.repositories` 没配 `mavenCentral()`；
  - 或者把文档里的版本占位符当成了真实版本。
- **修复**：
  - 使用 Maven Central 上已发布的插件与库版本，版本记录见 CHANGELOG。
  - 在 `pluginManagement` 和 `dependencyResolutionManagement` 中**分别**配置 `mavenCentral()`，不要依赖 `mavenLocal()`。见 [Klib Gradle 插件仓库](https://github.com/kzheart/klib-gradle-plugin)。

### `klib.main is not set`

- **症状**：构建失败并提示：

  ```text
  klib.main is not set: declare the Bukkit main class with main("com.example.MyPlugin") inside the klib { } block
  ```

- **原因**：`klib { }` 块里没调用 `main(...)`，该配置没有默认值。
- **修复**：补上 `main("com.example.plugin.ExamplePlugin")`。类名必须是全限定名，否则报 `klib.main is not a fully-qualified Java class`。DSL 全部配置项见 [Klib Gradle 插件仓库](https://github.com/kzheart/klib-gradle-plugin)。

### 模块方法无法解析

- **症状**：Kotlin DSL 编译失败，指出 `modules { }` 中的方法不存在。
- **原因**：模块方法拼写错误，或当前 Gradle 插件版本还没有提供该模块。
- **修复**：用 IDE 补全核对当前版本的模块方法。依赖会自动补全，不需要重复选择传递依赖。见 [Klib Gradle 插件仓库](https://github.com/kzheart/klib-gradle-plugin)。

## 启动与运行期

### 服务器启动报 `NoClassDefFoundError: me/kzheart/klib/...`

- **症状**：插件加载时找不到 Klib 类。
- **原因**：部署的是普通 `jar`，不是包含并重定位了 Klib 的 `shadowJar` 产物；服务器上也没有别的插件提供这些类。
- **修复**：执行 `./gradlew clean shadowJar`，部署 `build/libs/<project>-<version>-all.jar`。
  - 启用 `ketherInterop(true)` 时尤其注意：TabooLib 互操作入口**只存在于** `-all.jar` 中。
  - 详见 [Klib Gradle 插件仓库](https://github.com/kzheart/klib-gradle-plugin)。

### 插件在 1.12.2 上加载失败或产生 api-version 告警

- **症状**：同一个 JAR 在新版服务端正常，在 1.12.2 上加载异常。
- **原因**：生成的 `plugin.yml` 默认写入 `api-version: 1.13`，1.12.2 不接受这个键。
- **修复**：在 `klib { }` 中显式声明 `noApiVersion()`（等价于 `apiVersion("")`），不生成该键。基准版本能力差异见 [klib-compat](modules/compat.md)。

### 控制台出现「检测到另一个已激活的 KPlugin 实例」

- **症状**：插件被自动禁用，日志中出现：

  ```text
  检测到另一个已激活的 KPlugin 实例（<插件名>），本插件将被禁用：同一服务端只允许存在一份未重定位的 klib。
  ```

- **原因**：服务器上有两个插件都内嵌了**未重定位**的 `me.kzheart.klib`，静态实例互相抢占。
- **修复**：两个插件都用 `me.kzheart.klib` Gradle 插件打包，并各自设置**互不相同**的 `targetPackage`，让 Klib 重定位到 `<targetPackage>.libs.klib`。见 [Klib Gradle 插件仓库](https://github.com/kzheart/klib-gradle-plugin)。

### 改完配置执行重载后，插件的命令不再响应

- **症状**：`/xxx reload` 之后命令不再响应，客户端可能仍显示旧命令节点，甚至插件被禁用。
- **原因**：`config.onChange(root::rebuild)` 会关闭旧命令绑定，再重新执行包含命令声明的初始化逻辑。
  - 命令只支持在插件启动时声明。
  - 作用域关闭会立即停用执行、补全和权限检查，但不保证物理节点立即清理。
  - 运行时重建不能用来重新注册命令，也不能恢复被覆盖的标签。
- **修复**：
  - 配置重载只更新配置、语言和业务状态，命令处理器从稳定的服务入口读取最新值。
  - 需要重建监听器、任务等资源时，只重建**不含命令声明**的业务子作用域。
  - 移除根资源图重建，然后重启服务端，重新建立启动命令。
  - 见 [Command · 启动注册、配置重载与逻辑停用](modules/command.md#启动注册配置重载与逻辑停用) 和 [Core · 重建插件资源图](modules/core.md#重建插件资源图)。
- **日志里还有配置或资源安装错误时**，分开排查：

  | 日志 | 含义 |
  | --- | --- |
  | `configuration reload failed; keeping the last known good value` | 新值解析失败，沿用旧配置 |
  | `one or more reload listeners failed; the new configuration stays loaded` | 新值已加载，但监听器失败 |
  | 「插件重载失败，已关闭残留资源并禁用插件」 | `KPlugin` 根资源图重建失败，已清理残留资源并禁用插件 |

  详见 [Config · 重新加载](modules/config.md#重新加载)。

### 异步线程里调用 Bukkit API 抛异常或导致服务器不稳定

- **症状**：在 `async`、数据库回调或 `CompletionStage` 回调里读写玩家、背包、方块时报错。
- **原因**：Bukkit API 绝大多数只允许在服务器主线程访问，Klib **不会**替业务代码自动切线程。
- **修复**：

  | 场景 | 写法 |
  | --- | --- |
  | 异步结果送回主线程 | `scope.async(...).thenSync(...)` |
  | 从任意线程触发主线程任务 | `scope.sync(...)` |
  | 与 JDK `CompletionStage` 组合 | 把 `scope.syncExecutor()` 作为 executor |

  作用域关闭后提交的任务不会执行。详见 [Core · 调度](modules/core.md#调度)。

### 消息显示成键名，或语言意外回退到 `zh_CN`

- **症状**：启动日志出现 `找不到语言资源 lang/<locale>.yml，地区 <locale> 已回退到默认地区 zh_CN`。
- **原因**：`LangModule.install(...)` 请求的地区，在插件 JAR 类路径下没有对应的 `lang/<locale>.yml` 资源。
- **修复**：把该地区的语言文件放进 `src/main/resources/lang/`，或把 `locale` 参数改成实际提供的地区。
- 只是个别业务键缺失时，不会整体回退，渲染结果是红色的 `[missing:消息键]`。见 [klib-lang · 语言文件与回退](modules/lang.md#语言文件与回退)。
