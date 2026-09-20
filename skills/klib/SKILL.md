---
name: klib
description: 按 kzheart/klib 官方 Wiki 为 Bukkit、Spigot 或 Paper 插件接入 Klib、选用模块、编写功能与排查问题。适用于 Klib、KPlugin、Scope 和 me.kzheart.klib 依赖；不用于 Kotlin/Native 的 .klib 文件。
---

# Klib

使用 [Klib Wiki](https://github.com/kzheart/klib/wiki) 作为使用文档入口。本技能只提供文档导航，不保存 API 说明、版本快照或示例副本。

## 使用流程

1. 读取目标项目的指令、构建文件和插件入口，确认 Java、服务器 API、Klib 库及 Gradle 插件版本。
2. 读取 [Wiki 首页](https://github.com/kzheart/klib/wiki)，确认文档适用版本；再按任务读取下表对应页面，不必加载全部 Wiki。
3. 按页面提供的依赖、安装入口、生命周期和线程要求实现。保留目标项目的架构与外部插件约束。
4. 涉及多个模块时，读取各模块的初始化及资源释放要求。不要仅根据相似框架或记忆推断 Klib API。
5. 修改代码后执行与变更相符的编译、打包或运行验证；说明实际完成的验证，不把编译通过当作服务器运行通过。

## 文档导航

| 任务 | Wiki 页面 |
| --- | --- |
| 首次接入、最小插件 | [快速开始](https://github.com/kzheart/klib/wiki/Getting-Started) |
| Gradle、模块选择、依赖和打包 | [构建与打包](https://github.com/kzheart/klib/wiki/Build) |
| 生命周期、Scope、事件和调度 | [Core](https://github.com/kzheart/klib/wiki/Core) |
| YAML、重载、迁移和目录配置 | [Config](https://github.com/kzheart/klib/wiki/Config) |
| 语言文件、占位符和消息路由 | [Lang](https://github.com/kzheart/klib/wiki/Lang) |
| 命令树、参数、权限和补全 | [Command](https://github.com/kzheart/klib/wiki/Command) |
| 物品、标签、背包和编码 | [Item](https://github.com/kzheart/klib/wiki/Item) |
| 存储后端、事务和玩家缓存 | [Data](https://github.com/kzheart/klib/wiki/Data) |
| 物品栏菜单、分页和聊天输入 | [UI](https://github.com/kzheart/klib/wiki/UI) |
| Kether 脚本和语句互操作 | [Script](https://github.com/kzheart/klib/wiki/Script) |
| 经济插件和 PlaceholderAPI | [Hook](https://github.com/kzheart/klib/wiki/Hook) |
| 服务端版本与能力查询 | [Compat](https://github.com/kzheart/klib/wiki/Compat) |
| 日志、Incident 和异步交付 | [Remote](https://github.com/kzheart/klib/wiki/Remote) |
| Guard 商品公开生命周期 API | [Guard API](https://github.com/kzheart/klib/wiki/Guard-API) |
| 依赖、启动、重载和线程错误 | [故障排查](https://github.com/kzheart/klib/wiki/Troubleshooting) |

## 文档读取与版本差异

- 使用当前环境可用的网页读取或 HTTP 工具。需要 Markdown 正文时，页面 `Core` 对应 `https://raw.githubusercontent.com/wiki/kzheart/klib/Core.md`；其他页面按相同规则替换页面名，首页为 `Home.md`。
- 不依赖预先克隆的仓库、本机绝对路径或特定浏览器。Wiki 网页读取失败时尝试对应原始 Markdown；仍不可访问时说明无法核实的部分，不编造页面内容。
- Wiki 的适用版本与目标项目不一致，或缺少精确签名时，通过 Wiki 中的源码链接查对应版本的源码和测试。库与构建插件是独立仓库，不用其中一个版本号推导另一个，也不默认升级项目。
- 回答中链接到实际使用的 Wiki 页面。资料不足或未运行验证时明确区分已确认事实与尚未验证的行为。
