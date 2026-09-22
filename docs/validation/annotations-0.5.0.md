# 0.5.0 注解 API 验证记录

日期：2026-09-22。范围：公共 Java 模块及独立 annotated 示例；未部署生产、未发布 Maven Central。

## 自动化验证

```bash
./gradlew clean check publishToMavenLocal centralDryRunBundle --no-configuration-cache
./gradlew -p examples/annotated-klib-plugin -PklibSource check --no-configuration-cache
```

开发工作区及基于 GitHub 主分支的发布工作区均有 427 项测试通过，0 失败、0 错误、0 跳过。
覆盖命令权限/参数/补全/别名/批量回滚/重建、组件初始化失败清理、事件注销、异步周期不重叠、回调线程与关闭抑制、配置原子重载、菜单布局与刷新。
制品校验验证 Java 8 字节码、Klib 重定位和不打包 Bukkit；另从重定位 JAR 调用反射声明检查，确认 private 注解方法仍会被拒绝。
Central dry run 只生成并校验本地上传包，没有向 Central 发布。

## 真实 Minecraft 验证

MC Pilot 0.16.0；独立 Paper 1.20.4 build 496 + Fabric 1.20.4 客户端，真实进入世界；该组合通过本次实际连接验证。
宿主运行 Java 25；Java 8 兼容性由 --release 8、依赖及最终 JAR 字节码检查验证，本次未逐个启动所有受支持的 Minecraft/Java 组合。

| 场景 | 观察结果 |
| --- | --- |
| 玩家加入 | 收到注解事件的 ANNOTATED_EVENT_JOIN |
| 别名 + 贪婪参数 | `/kan search hello annotation world` 返回完整文本 |
| 类型参数 | UUID 转为类型参数，副本/难度路径执行成功 |
| 异步结果 | ASYNC=ok main=true |
| 非 OP 管理入口 | 返回没有权限 |
| 菜单翻页 | 钻石数量 1→2；syncId 不变；重新打开恢复为 1 |
| 非法配置 | party-size=30 被拒绝，保留之前的 4 |
| 业务检查 | party-size=1 时 search 返回 BUSINESS_NOT_READY |
| 组件关闭 | ka/kan 注销，同步/异步计数停止，管理入口仍可用 |
| 重建 | 新组件恢复，别名和命令可用 |
| 打开菜单时重建 | 菜单关闭，客户端报告 GUI_NOT_OPEN |

非法配置测试预期产生 ConfigMappingException，并记录保留旧值；这不是未处理的测试失败。
带前置参数的补全及 Brigadier 可见性由自动化测试验证，本次没有通过客户端键盘 Tab 操作验证。
测试后停止独立客户端和服务端。

示例 JAR SHA-256（与测试服务端部署文件一致）：

```
abb2d2a9dd53ae7e78c0b5524aa294e14938242d4c5a9d03ce8e10a8344d5db1
```

## 2026-09-23 正式发布补充

Klib 0.5.0 已通过 [正式发布流程](https://github.com/kzheart/klib/actions/runs/35766668624) 发布至 Maven Central，Central 最终状态为 `PUBLISHED`。标签 `klib-v0.5.0` 对应提交 `3cacd87b6d1d98ccd67cdb27bfcdf8d658f6eadc`；Guard API 保持 0.2.0。

发布后省略 `-PklibSource`，执行以下命令，从 Maven Central 解析 0.5.0 后构建成功，并通过示例制品检查（Java 8 字节码、重定位及不包含 Bukkit）：

```bash
./gradlew -p examples/annotated-klib-plugin clean check --refresh-dependencies --no-configuration-cache
```

上方 2026-09-22 的记录保留当日验证范围；本次正式发布没有重复执行真实 Minecraft 场景。

发布后逐个下载核对全部 19 个普通模块的 POM、JAR、sources、Javadoc 和 JAR 签名文件，所有 JAR 的 SHA-1 与 Central 公布值一致。
