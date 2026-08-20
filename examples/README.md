# Klib 示例

这里保留五个可阅读、可独立构建的 Bukkit/Paper 示例。既有示例通过各自的 `standalone/` 构建；两个
远程解析示例直接提供完整的独立 Gradle 项目。

| 示例 | 内容 |
| --- | --- |
| [`empty-plugin`](empty-plugin) | `Scope` 生命周期、配置、语言和命令的最小组合 |
| [`SimpleGather-klib`](SimpleGather-klib) | 命令、物品、外部插件挂钩和 Remote 客户端 |
| [`SimpleStall-klib`](SimpleStall-klib) | 数据、UI、脚本和异步回主线程的完整组合 |
| [`remote-klib-plugin`](remote-klib-plugin) | 仅从 Plugin Portal 与 Maven Central 构建的普通 Klib 插件 |
| [`remote-klib-cloud-plugin`](remote-klib-cloud-plugin) | 仅从 Maven Central 编译的 Guard 云端商品业务 JAR |

以最小示例为例：

```bash
./gradlew -p examples/empty-plugin/standalone clean shadowJar
```

产物输出到 `examples/empty-plugin/build/libs/`。另外两个示例用相同方式把路径替换为对应目录。

示例中的日志探针、固定商品名和测试命令只用于展示能力，不应原样复制到正式插件。生产项目必须使用
自己的包名、权限、配置结构和 Remote Key；密钥与 Token 不得写入仓库。

## 验证远程解析

以下命令会为两个远程示例分别创建空的 Gradle 与 Maven 缓存，重新下载全部插件和依赖，并检查产物
边界：

```bash
./scripts/verify-remote-examples.sh
```

普通示例生成可直接放入 Paper/Spigot `plugins/` 目录的 `-all.jar`。云端示例生成未授权加工的业务
JAR，只能作为 Guard/Collector 发布流程的输入，不能直接作为 Bukkit 插件运行。
