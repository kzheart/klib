# 原生 Kotlin 改造验证计划

本计划先于实现编写；计划中的用例不代表已经执行。当前结果与原始证据记录在独立 QA 工作目录，最终报告必须按最终提交重新关联。

## 目标与范围

验证现有 Core、Config、Command、UI、Item、Data、Script 原生 Kotlin API、默认 Kotlin JVM 构建、运行库隔离与 metadata、构造器配置、每代协程作用域，以及共享侧边栏适配器。保留 Java 8 公共边界，不引入 Guard 私有实现。

每条结果使用 PASS / FAIL / BLOCKED / NOT_RUN / NOT_APPLICABLE，并记录用例 ID、源码提交或工作树摘要、产物 SHA-256、JDK、服务端与客户端独立版本、前置条件、输入、预期、实际、日志/截图以及未测试原因。工具自身测试不计入 Klib 通过数。

## SFDIPOT 风险矩阵

| 维度 | 风险与分区 | 验证及证据 |
| --- | --- | --- |
| Structure 结构 | 模块闭包、重复 runtime、Java/Kotlin 互调、重定位 metadata、编译产物 | S01 Java API/字节码检查；S02 Kotlin consumer 编译；S03 两插件不同隔离包同服；S04 jar 类名、服务文件、kotlin_module 与反射探测 |
| Function 功能 | 安装 DSL、命令、配置、事件、任务、菜单、物品、持久化、脚本 | F01–F07 各模块编译/单测；F08 Kotlin 探针真实玩家操作；F09 侧边栏创建/差异更新/清除 |
| Data 数据 | 缺失/显式 null/默认值、类型错误、UTF-8、集合、重复 ID、损坏文件 | D01–D06 构造器分区；D07 存储 CRUD 与重启；D08 item codec 往返与非法 tag；D09 脚本无效输入 |
| Interfaces 接口 | Java/Kotlin、Bukkit、反射、GUI、数据库、文件、MCT | I01 Java consumer；I02 Kotlin default/named 参数；I03 GUI 点击与事件；I04 数据源关闭归属；I05 metadata 反射 |
| Platform 平台 | Java 8 字节码、服务端 JDK、四类 compat、宿主依赖冲突 | P01 class major<=52；P02 版本边界；P03 实服分代；P04 两插件隔离；P05 Spigot/Paper 差异明确列出 |
| Operations 操作 | 启停、重载、取消、掉线、重复注册、清理、失败恢复 | O01 生命周期状态机；O02 活跃任务重载；O03 菜单关闭/退出；O04 重复 close；O05 错误配置原子回滚 |
| Time 时间 | 延迟、并发、取消竞态、旧代回调、同步主线程约束 | T01 delay 前/中/后取消；T02 重载期间恢复；T03 旧代永不触发；T04 async→main 切换；T05 连续快速点击/重载 |

## 等价类与边界

- 配置：字段缺失、显式 null、合法值、错型；required/non-null/default/nullable；嵌套对象/空列表/单元素/多元素；数值 min-1/min/min+1、max-1/max/max+1；中文、emoji、空串、超长串。校验默认参数确由构造器执行，错误保留路径且原状态不变。
- 命令：无参数、最少参数、完整参数、多余参数、非法数值、溢出；玩家/控制台、有权限/无权限；补全与执行一致；业务异常与内部异常分开。
- UI：槽位 -1/0/最后有效/越界；页大小 0/1/满页/满页+1；左/右/shift/双击/拖拽；连续打开/返回/关闭/掉线；拒绝操作不复制或丢失物品。
- Item：数量 0/1/上限/上限+1；存在/未知材质；空/有效/损坏编码；标签缺失/错型；clone 修改不污染原物品。
- Sidebar：0/1/14/15/16 行；重复行、空行、色码、16/17/32/33 字符旧版边界；同值更新应无差异发送；离线玩家忽略；不替换 Bukkit scoreboard。
- 协程：未启动/运行/挂起/完成/失败/取消；父失败传播与 sibling 行为以公开契约为准；取消后 launch/reload/close 的行为可预测且有证据。
- 版本解析：1.12.1/1.12.2、1.19.4/1.20.0/1.20.3/1.20.4、1.20.6/1.21.0、26.1.2/26.2.0/26.9/27.0，空/坏字符串与 duplicate provider。

## 生命周期状态转换与非法路径

| 初态 | 操作 | 预期 |
| --- | --- | --- |
| 新建 | enable/setup | 唯一活跃代；模块与监听各安装一次 |
| 活跃 | reload 成功 | 旧代任务/监听/菜单释放，新代正常；旧回调不可再变更状态 |
| 活跃 | reload 配置失败 | 按原子契约保持旧有效配置；无半安装资源 |
| 活跃/挂起 | disable/close | 子 job 取消，任务不再执行，资源释放 |
| 已关闭 | 重复 close | 幂等或明确契约错误，无新副作用 |
| 已关闭 | launch/show/register | 明确拒绝，无僵尸任务或包发送 |
| 活跃 | 玩家退出→重进 | 清除旧会话状态，新 UUID 会话正确重新创建 |
| enable 失败 | rollback→disable | 不泄漏 listener/task/classloader；错误带原始原因 |

## CRUD 覆盖

配置：创建缺省→读取→修改/成功重载→删除字段回默认或报缺失→损坏回滚。数据：insert→read→update→delete→read absent→restart 验证；重复键、缺失键、事务失败、并发写。UI/session：create→inspect→update→close→closed-access。物品/tag：create→decode/read→replace→remove→round-trip。侧边栏：show→query→diff update→hide→hide again；每项核对实际状态与事件计数。

## 探索章程

1. X01 探索生命周期重载与竞态，使用挂起任务、打开菜单、异步数据操作以及重复重载/禁用/退出，以发现旧代继续写入、重复监听与线程错误。记录每代 ID、任务计数与最终数据。
2. X02 探索恶意与意外输入，使用边界槽位、shift/drag/双击、错型配置、命令溢出和损坏 item/script，以发现重复奖励、数据损坏与不可恢复界面。记录拒绝前后 inventory、消息与异常。
3. X03 探索双插件隔离，使用不同 targetPackage 的 Kotlin 探针同时加载、交替配置反射/协程/脚本/重载，以发现 metadata 指向旧包、LinkageError、跨插件取消与共享状态。
4. X04 探索跨代视觉一致性，使用旧版和现代客户端执行相同菜单/侧边栏序列并截图，以发现颜色、截断、重复行、分页导航、退出重进缺陷。分别保留截图与客户端状态，不能以服务端日志代替视觉证据。

## HICCUPPS 缺陷判定依据

- History：改造前已通过用例与行为；改变行为必须有明确新契约
- Image：文档承诺的原生、简洁 Kotlin 用法可直接编译使用
- Comparable products：只作辅助，优先 Klib 当前契约，不强行复制其他库行为
- Claims：README、模块文档、DSL 与异常契约
- User expectations：无需 Java 式包装即可使用；重载不残留；两个插件不互相影响
- Product：Java/Kotlin API、配置/运行时、GUI/命令之间一致
- Purpose：模块化、生命周期自动释放、跨版本插件开发目标
- Statutes：Apache/第三方 NOTICE、Minecraft EULA；不擅自接受新协议

## 支持范围与执行顺序

根 README 仅承诺 Bukkit/Spigot/Paper 与 Java 8 公共字节码，不枚举 Minecraft 版本。当前 compat 文档及实现声明：v1_12=[1.12.2,1.20.0)，v1_20 实际解析=[1.20.4,1.21.0)，v1_21=[1.21.0,26.2.0)，v26=[26.2.0,27.0.0)。解析范围不等于实服验证。

历史侧边栏文档列出 Paper 1.12.2、1.16.5（无截图）、1.18.2、1.20.4、1.21.1、1.21.4、1.21.11、26.1.2、26.2；本次须重新测，不能继承为本次通过。

先做静态/构建/单测→隔离 consumer 包装→Paper 1.21.4 实服探针→1.20.4→1.12.2→26.2→补充历史基准与负例。1.20.0–1.20.3 属明确不支持，应测试拒绝。Spigot 和未执行 patch 版本必须列为未测。

## 环境与退出门槛

初查云端具备 JDK 21、Node、Python、FFmpeg、Mesa GL；无 mct 安装、DISPLAY/Xvfb、服务器/客户端缓存或已接受 EULA。mc-pilot 官方 HEAD 0425bcf，CLI 0.20.0；27 个客户端 variant 不是 Klib 测试成绩。新 EULA 必须获授权；无权限时实服标 BLOCKED，继续其余验证。

最终必须运行仓库要求的 check/publishToMavenLocal/centralDryRunBundle（后两者依赖可用配置），以及 Gradle 插件验证；每个失败和未测项给具体原因、影响和补测入口。所有实服使用隔离临时项目、回环地址，不接触用户电脑或生产；结束后停止自有进程。不得把计划、模拟、协议 bot、工具单测报告为真实客户端通过。
