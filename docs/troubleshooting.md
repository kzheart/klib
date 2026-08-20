# 故障排查

本页按「症状 → 原因 → 修复」收录 Klib 插件开发中最常见的构建和启动问题。

## 构建期

### `Could not find de.tr7zw:item-nbt-api`

- 症状：选择了 `item` 或 `ui` 模块后，依赖解析失败于 `de.tr7zw:item-nbt-api`。
- 原因：`klib-item` 的运行时依赖只在 CodeMC 仓库发布，`klib-ui` 又依赖 `klib-item`；使用方仓库
  列表里没有 CodeMC。
- 修复：在 `settings.gradle.kts` 的 `dependencyResolutionManagement.repositories` 中加入
  `maven("https://repo.codemc.io/repository/maven-public/")`，见
  [Klib Gradle 插件仓库](https://github.com/kzheart/klib-gradle-plugin)。

### `Could not find me.kzheart.klib:klib-*` 或插件 ID `me.kzheart.klib` 无法解析

- 症状：`id("me.kzheart.klib") version "..."` 或 `me.kzheart.klib:klib-core:...` 找不到。
- 原因：`pluginManagement.repositories` 或 `dependencyResolutionManagement.repositories` 没有配置
  `mavenCentral()`；也可能把文档里的版本占位符当成了真实版本。
- 修复：Gradle 插件使用已发布的 `0.5.0`，普通模块使用 `0.4.0`。在 `pluginManagement` 和
  `dependencyResolutionManagement` 中分别配置 `mavenCentral()`，不要依赖 `mavenLocal()`，见
  [Klib Gradle 插件仓库](https://github.com/kzheart/klib-gradle-plugin)。

### `klib.main is not set`

- 症状：构建失败并提示
  `klib.main is not set: declare the Bukkit main class with main("com.example.MyPlugin") inside the klib { } block`。
- 原因：`klib { }` 块里没有调用 `main(...)`；该配置没有默认值。
- 修复：补上 `main("com.example.plugin.ExamplePlugin")`。类名必须是全限定名，否则会报
  `klib.main is not a fully-qualified Java class`。DSL 全部配置项见
  [Klib Gradle 插件仓库](https://github.com/kzheart/klib-gradle-plugin)。

### 模块方法无法解析

- 症状：Kotlin DSL 编译失败，并指出 `modules { }` 中的方法不存在。
- 原因：模块方法拼写错误，或当前 Gradle 插件版本尚未提供该模块。
- 修复：使用 IDE 补全并核对当前版本的模块方法。依赖会自动补全，不需要重复选择传递依赖，见
  [Klib Gradle 插件仓库](https://github.com/kzheart/klib-gradle-plugin)。

## 启动与运行期

### 服务器启动报 `NoClassDefFoundError: me/kzheart/klib/...`

- 症状：插件加载时找不到 Klib 类。
- 原因：部署的是普通 `jar`，而不是包含并重定位了 Klib 的 `shadowJar` 产物；服务器上没有任何插件
  提供这些类。
- 修复：执行 `./gradlew clean shadowJar`，部署 `build/libs/<project>-<version>-all.jar`。
  启用 `ketherInterop(true)` 时更要注意：TabooLib 互操作入口只存在于 `-all.jar` 中。详见
  [Klib Gradle 插件仓库](https://github.com/kzheart/klib-gradle-plugin)。

### 插件在 1.12.2 上加载失败或产生 api-version 告警

- 症状：同一个 JAR 在新版服务端正常，在 1.12.2 上加载异常。
- 原因：生成的 `plugin.yml` 默认写入 `api-version: 1.13`，1.12.2 不接受该键。
- 修复：在 `klib { }` 中显式声明 `noApiVersion()`（等价于 `apiVersion("")`），表示不生成该键。基准版本能力差异见
  [klib-compat](modules/compat.md)。

### 控制台出现「检测到另一个已激活的 KPlugin 实例」

- 症状：插件被自动禁用，日志中出现
  `检测到另一个已激活的 KPlugin 实例（<插件名>），本插件将被禁用：同一服务端只允许存在一份未重定位的 klib。`
- 原因：服务器上有两个插件都内嵌了**未重定位**的 `me.kzheart.klib`，静态实例互相抢占。
- 修复：两个插件都用 `me.kzheart.klib` Gradle 插件打包，并各自设置互不相同的 `targetPackage`，
  让 Klib 被重定位到 `<targetPackage>.libs.klib`，见
  [Klib Gradle 插件仓库](https://github.com/kzheart/klib-gradle-plugin)。

### 改完配置执行重载后，插件的命令全部消失

- 症状：`/xxx reload` 之后命令不再响应，甚至插件被禁用。
- 原因：`config.onChange(root::rebuild)` 会在配置变更后重建整张资源图。新配置本身能解析，但重建
  过程中某个资源安装失败，Klib 会清理残留资源并禁用插件；如果只是监听器抛异常，新配置仍保持
  加载，但依赖它的资源可能没重建完整。
- 修复：查看控制台的 SEVERE 日志。`YamlConfigDocument` 会分别记录
  `configuration reload failed; keeping the last known good value`（新值解析失败，沿用上一份可用
  配置）和 `one or more reload listeners failed; the new configuration stays loaded`（新值已加载但
  监听器失败）；`KPlugin` 重建失败时记录「插件重载失败，已关闭残留资源并禁用插件」。按日志里的
  根因修正配置或资源安装逻辑，见 [klib-config · 重新加载](modules/config.md#重新加载) 和
  [Core · 重建插件资源图](modules/core.md#重建插件资源图)。

### 异步线程里调用 Bukkit API 抛异常或导致服务器不稳定

- 症状：在 `async`、数据库回调或 `CompletionStage` 回调里读写玩家、背包、方块时报错。
- 原因：Bukkit API 绝大多数只允许在服务器主线程访问，Klib 不会替业务代码自动切线程。
- 修复：用 `scope.async(...).thenSync(...)` 把结果送回主线程；从任意线程触发时用 `scope.sync(...)`；
  与 JDK `CompletionStage` 组合时用 `scope.syncExecutor()` 作为 executor。作用域关闭后提交的任务
  不会执行。详见 [Core · 调度](modules/core.md#调度)。

### 消息显示成键名，或语言意外回退到 `zh_CN`

- 症状：启动日志出现 `找不到语言资源 lang/<locale>.yml，地区 <locale> 已回退到默认地区 zh_CN`。
- 原因：`LangModule.install(...)` 请求的地区在插件 JAR 的类路径下没有对应
  `lang/<locale>.yml` 资源。
- 修复：把该地区的语言文件放进 `src/main/resources/lang/`，或把 `locale` 参数改成实际提供的地区。
  如果只是个别业务键缺失，渲染结果会是红色的 `[missing:消息键]` 而不是整体回退，见
  [klib-lang · 语言文件与回退](modules/lang.md#语言文件与回退)。
