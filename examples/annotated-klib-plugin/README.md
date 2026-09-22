# 组件与注解综合示例

对应 Klib **0.5.0 开发源码**，没有依赖尚未发布的远程制品。Java 8 字节码，示例目标服务端 Paper 1.20.4。

在仓库根目录构建：

```bash
./gradlew -p examples/annotated-klib-plugin -PklibSource check --no-configuration-cache
```

产物：`build/libs/annotated-klib-plugin-1.0.0-all.jar`（相对于示例目录），已经通过 Klib Gradle 插件重定位。
`-PklibSource` 明确将 Klib 模块替换为本仓库源码；将来 0.5.0 发布后可省略该参数从公共仓库解析。

## 验证入口

| 操作 | 预期 |
| --- | --- |
| 玩家进入服务器 | 收到 `ANNOTATED_EVENT_JOIN` |
| `/kan search hello world` | 别名生效，返回 `SEARCH=hello world` |
| `/ka select castle ` 后补全 | 仅提供 `castle-easy` |
| `/ka resolve <UUID>` | 返回解析后的 UUID，非法格式被拒绝 |
| `/ka async` | 返回 `ASYNC=ok main=true` |
| `/ka ui` 后点击箭头 | 第一槽钻石数量增加，物品栏会话不变；重新打开恢复为 1 |
| 非 OP 执行 `/kadmin status` | 没有权限 |
| OP 或控制台执行 `/kadmin status` | 显示配置、同步/异步任务次数和事件数 |
| config.yml 设置 `party-size: 30` 后 `/kadmin reload` | 校验拒绝，保留之前的有效值 |
| 设置 `party-size: 1` 后 reload，再执行 search | 业务检查返回 `BUSINESS_NOT_READY` |
| `/kadmin stop` | 副本组件命令/别名注销，同步/异步任务停止；管理命令仍可用 |
| `/kadmin rebuild` | 重建插件上下文并恢复新组件，无重复注册 |

本示例的 Menus 安装在插件根上下文，组件 stop 不关闭根菜单服务；插件 rebuild/disable 会关闭它。
需要组件关闭即关闭菜单时，将 Menus 安装放在组件 setup 中并传入组件 context。
测试专用管理权限为 `klib.example.admin`，请勿原样复制测试管理入口到生产插件。

详见 [组件与注解 API](../../docs/annotations.md)。
