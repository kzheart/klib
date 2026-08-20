# Remote Klib Cloud Plugin Example

这是一个由 Klib Guard 加载的最小云端商品示例。它在玩家加入时发送欢迎消息，并把累计加入次数保存到
商品自己的数据目录。

## 构建

在本目录执行：

```bash
../../gradlew clean check jar
```

产物位于 `build/libs/remote-klib-cloud-plugin-0.1.0.jar`。

构建只从 Maven Central 和 Spigot 仓库解析依赖，不使用 `mavenLocal()`、复合构建、本地项目依赖或
本地 JAR。`klib-guard-api` 通过 Maven POM 传递提供编译所需的 `klib-core`；它们和 Spigot API 都不会
被打入商品 JAR。

该产物是普通、未授权加工的业务 JAR，可作为后续 Guard/Collector 流程的输入。入口类由
`META-INF/klib-guard/entrypoint` 声明；它不是 Bukkit 插件，因此不包含 `plugin.yml`，也不能直接放入
服务器的 `plugins` 目录运行。
