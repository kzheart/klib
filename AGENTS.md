# Klib 公共仓库协作指南

## 沟通与边界

- 始终使用简体中文沟通；代码标识、命令和配置键保留原文。
- 本仓库只维护 Apache-2.0 公共 Java 模块和 `klib-guard-api`。
- 禁止加入 Guard runtime、Native、Collector、部署配置、生产密钥或授权内部文档。
- Gradle 插件在 `kzheart/klib-gradle-plugin` 独立维护，不在这里复制源码。

## 工程约束

- 所有公共 Java 产物必须保持 Java 8 字节码以及 Java 8 语法/API 边界。
- 实现事实以当前源码、测试和构建配置为准；公开行为变化必须同步更新对应模块文档。
- `klib-compat-v*` 是兼容实现，使用者通过 `klib-compat` 接入。
- `klib-guard-api` 只包含公开生命周期契约，不得引入协议、JNI 或运行时实现。
- 不新增未要求的旧 API 别名、降级路径或兼容层。

## 验证

```bash
./gradlew clean check publishToMavenLocal --no-configuration-cache
./gradlew centralDryRunBundle --no-configuration-cache
```

修改文档后检查相对链接。

## 文档同步

每次改动完成后，在同一次任务内同步更新以下文档，不要留到之后：

1. 仓库文档：受影响的 `docs/modules/*.md`、根 `README.md`（模块表、接入方式）和 `CHANGELOG.md`。
2. GitHub Wiki（`https://github.com/kzheart/klib.wiki.git`）：Wiki 页面是 `docs/modules` 的改写版，
   例如 `docs/modules/core.md` 对应 Wiki 的 `Core.md`。按相同内容更新对应页面，把仓库内相对链接改成
   Wiki 页面链接（如 `../annotations.md` 写作 `Annotations`），新增页面时同步更新 `_Sidebar.md`。
   Wiki 需单独 clone 到临时目录修改，提交信息同样使用 Conventional Commits，然后推送。

内容边界：

- 不提供示例工程，仓库中不得新增 `examples/` 或其他示例项目；用法说明只写在模块文档与 Wiki 中。
- 根 `README.md`、模块文档和 Wiki 都只描述当前最新状态，不写具体版本号、版本专属章节、“适用于某版本”
  说明或“未发布”标注；代码片段中的版本用 `<klib-version>`、`<gradle-plugin-version>`、
  `<guard-api-version>` 等占位符。版本信息只维护在 `CHANGELOG.md` 与 `gradle.properties`。
- 文档与 Wiki 的示例代码必须使用当前推荐的 API（例如 `KPlugin` 的无参 `setup()` 与
  `commands()`/`configs()`/`events()`/`tasks()`），旧写法只在确有需要的高级场景中出现并说明原因；
  API 变化后同步改写受影响的示例代码。

版本规则：

- 已发布版本不包含的功能写入 `CHANGELOG.md` 的“未发布”段，不得写进已发布版本号下。
- 发布新版本时，更新 `gradle.properties` 中的版本，把“未发布”段改为正式版本号与日期，再按
  `docs/releasing.md` 打 tag 发布。
- 动手前先确认当前分支基于最新的 `origin/main`，并以最新 tag 判断哪个版本已经发布。

## Git

- 提交信息使用 Conventional Commits：`type(scope): 中文描述`。
- `type` 仅使用 `feat`、`fix`、`test`、`docs`、`refactor`、`perf`、`chore`、`build`、`ci`、
  `style`、`revert`。
- 保留用户已有改动，不覆盖或回滚无关文件。
