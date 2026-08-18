# Klib 示例

这里保留三个可阅读、可独立构建的 Bukkit/Paper 示例。每个示例的 `standalone/` 都是完整 Gradle 构建，
从 Gradle Plugin Portal 获取 `me.kzheart.klib` 插件，并从 Maven Central 获取公共模块，不依赖本仓库的
构建缓存或私有制品。

| 示例 | 内容 |
| --- | --- |
| [`empty-plugin`](empty-plugin) | `Scope` 生命周期、配置、语言和命令的最小组合 |
| [`SimpleGather-klib`](SimpleGather-klib) | 命令、物品、外部插件挂钩和 Remote 客户端 |
| [`SimpleStall-klib`](SimpleStall-klib) | 数据、UI、脚本和异步回主线程的完整组合 |

以最小示例为例：

```bash
./gradlew -p examples/empty-plugin/standalone clean shadowJar
```

产物输出到 `examples/empty-plugin/build/libs/`。另外两个示例用相同方式把路径替换为对应目录。

示例中的日志探针、固定商品名和测试命令只用于展示能力，不应原样复制到正式插件。生产项目必须使用
自己的包名、权限、配置结构和 Remote Key；密钥与 Token 不得写入仓库。
