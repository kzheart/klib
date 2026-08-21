# Remote Klib Cloud Plugin Example

这是一个由 Klib Guard 加载的最小云端商品示例。它在玩家加入时发送欢迎消息，并把累计加入次数保存到
商品自己的数据目录。

## 构建

在本目录执行：

```bash
../../gradlew clean guardProductJar
```

上传产物位于 `build/libs/remote-klib-cloud-plugin-0.1.0-guard.jar`；依赖体积与条目报告位于
`build/reports/klib/bundle-report.txt`。

构建使用 Klib Gradle 插件 `0.5.1` 的 `guardProduct {}` DSL，只从 Plugin Portal、Maven Central 和
Spigot 仓库解析依赖，不使用 `mavenLocal()`、复合构建、本地项目依赖或本地 JAR。插件自动加入
`klib-guard-api`，生成入口描述符，并把 Guard API、Klib Core 与 Spigot API 保持为宿主提供依赖。

`guardProductJar` 会在发布前执行 Collector 同源边界校验，失败时不会在 `build/libs` 留下可上传产物。
通过验证的 JAR 包含 `META-INF/klib-guard/entrypoint`，不包含 `plugin.yml`、宿主 API、原生库或嵌套
JAR；它只能上传到 Guard/Collector，不能直接放入服务器的 `plugins` 目录运行。
