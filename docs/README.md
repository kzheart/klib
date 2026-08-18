# Klib 文档

本目录是 Klib 公共 Java 模块的文档入口。根目录 [README](../README.md) 提供最小接入；这里按模块
说明完整 API 边界、生命周期、线程要求和版本约束。

推荐使用独立的 [Klib Gradle 插件](https://github.com/kzheart/klib-gradle-plugin) 选择模块、生成
`plugin.yml` 并打包重定位依赖。需要完全手工管理依赖时，可直接使用 Maven Central 上的
`me.kzheart.klib:klib-*:0.2.0` 坐标。

## 模块文档

| 模块 | 文档 |
| --- | --- |
| Core | [klib-core](modules/core.md) |
| 配置 | [klib-config](modules/config.md) |
| 语言 | [klib-lang](modules/lang.md) |
| 命令 | [klib-command](modules/command.md) |
| 物品 | [klib-item](modules/item.md) |
| 数据 | [klib-data](modules/data.md) |
| UI | [klib-ui](modules/ui.md) |
| 脚本 | [klib-script](modules/script.md) |
| 外部插件集成 | [klib-hook](modules/hook.md) |
| 版本能力 | [klib-compat](modules/compat.md) |
| 远程诊断客户端 | [klib-remote](modules/remote.md) |
| Guard 编译契约 | [klib-guard-api](modules/guard-api.md) |

## 跨模块专题

- [Remote 协议 v1](remote-protocol.md)
- [Remote 安全边界](remote-security.md)
- [故障排查](troubleshooting.md)
- [示例工程](../examples/README.md)
- [Maven Central 发布](releasing.md)

Guard runtime、Native、Collector、Remote 服务端和生产部署文档属于私有仓库，不在本公共源码仓库中。
