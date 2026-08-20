# Remote Klib Plugin

这是一个独立的 Bukkit 插件项目，用于演示从 Gradle Plugin Portal 获取
`me.kzheart.klib` 0.2.0，并由插件从 Maven Central 解析 Klib 模块。

示例通过类型安全的 `modules { command() }` DSL 选择命令模块。Klib Gradle 插件会生成
`plugin.yml`，并把 Klib 依赖重定位到最终 JAR 中。插件启动时创建一个由 `Scope` 管理的会话，
注册 `/klibhello` 命令，并在插件关闭时自动释放会话和注销命令。

在 Klib 仓库根目录执行：

```bash
./gradlew -p examples/remote-klib-plugin clean shadowJar --refresh-dependencies
```

最终插件位于 `examples/remote-klib-plugin/build/libs/remote-klib-plugin-1.0.0-all.jar`。
该构建不使用 `mavenLocal()`、`includeBuild`、本地项目依赖或本地 JAR。
