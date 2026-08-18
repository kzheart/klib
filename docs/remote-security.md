# Remote 安全边界

Remote 是独立的诊断产品，不参与 Guard 授权、服务器绑定、风险判定或制品信任。Remote 的安装 ID、
公开 Key 和事件都不能用来证明插件来源、正版性、用户身份或事件真实性。

## 客户端责任

`rpk_live_` / `rpk_test_` 是可嵌入 JAR 的公开写入 Key，可读取 settings 和写入该商品事件；任何拿到
它的人都可能尝试上报。因此不要把它当密码、签名或访问控制凭据，也不要在 Remote 上作 source-authenticity
判断。创建、暂停、吊销、回看完整 Key 和读取正文必须走受认证的 control 平面。

Incident 因果视图保留来源语义，不把相关性冒充真实性：`linked` 只来自同一安装内显式的 operation
同一节点或直接父子边；`nearby` 只表示前后 60 秒内的 Incident / error 日志；`confirmed_cause` 只来自
结构化 `payload.confirmed_cause` marker。服务端不使用时间接近、文本相似、Throwable、operation outcome
或 Contributor 自动宣布根因。由于 `rpk_*` 是公开写入 Key，installation、operation 边、客户端时间和
`confirmed_cause` 都可由写入方声明；三档均不是服务端验证或 Guard 认证过的事实，控制台必须保留档位
含义，尤其不能把 `nearby` 提升为原因。

Remote 不做 `Redactor`、平台级脱敏或通用内容过滤。`RemoteLogger` 的 message、context、MDC、tags、
Breadcrumb、Throwable message/stack、Contributor 输出和 `RemoteEvent` payload 会按插件代码提供的内容发送。
开发者必须只放入排障所需的最小信息，并避免玩家文本、命令、聊天、密码、Token、配置正文、绝对路径及
个人信息。详见 [klib-remote](modules/remote.md)。

`RemoteDelivery` 在本地磁盘保存完整事件，默认最长 24 小时。队列目录应位于插件私有数据目录，其整条
父目录链不得允许 group/other 或非 owner 写入；文件系统还必须能落实并验证 owner-only POSIX 权限或 ACL
及稳定目录身份，否则客户端拒绝启用磁盘队列。目录或父目录权限运行时改变也会停止后续操作。服务端
操作者和备份流程都应把它视为敏感数据；删除目录会丢弃未交付事件。

## 服务端与租户

ingest 仅接受严格的 gzip JSON v1。在查询 Key 或解压 batch 前先执行来源 IP 与 Key selector 的持久化
请求预算，之后再执行请求大小、字段长度、Key/IP/安装/ASN/商品突发限流和每月事件/字节预算；这只能
限资源滥用，不能使公开 Key 成为可信身份。字节预算按事件规范化正文、提取字段、Incident 副本与该事件
触发的待发通知密文计算确定性逻辑成本，并包含保守行开销；预算预警和每日汇总通知自身不重复计入该事件
字节，但分别受每商品 16 个订阅与 outbox 清理周期硬限制。逻辑预算不是 PostgreSQL 页面、索引或 WAL 的
精确磁盘计量。

control 同时区分角色和身份来源。官方 `developer` 仅能操作自己的商品，官方 `admin` 只能读元数据，
官方 `root` 可做平台治理但不能替开发者修改 Issue；本地 admin/root 是自托管管理员，可管理实例并修改
Issue；本地 developer 只能查询自己商品的正文、导出和修改 Issue，不能管理或删除数据。官方身份永远
不能访问部署级 `/control/v1/system/*`。会话 Cookie 为 HttpOnly、Secure、SameSite=Strict，所有非安全
方法还要求 `X-CSRF-Token`。

公开 Key 的完整值不是秘密，但批量取得所有租户的写入 Key 仍会扩大事件注入面。因此商品 Key 列表、平台
全局列表和数据导出只返回掩码，完整值必须通过单资源
`GET /control/v1/products/{product}/keys/{key}/token` 主动回看。该端点与 Key 创建、状态变更使用同一
`manage_key` 权限：官方 owner developer 与官方 root、本地 admin/root 可用；官方 admin、本地 developer
不可用。查询同时绑定商品与 Key ID，跨租户请求不会泄露 Key 是否存在。

会话恢复端点只通过 HttpOnly Cookie 返回本地用户名、角色、绝对过期时间和新 CSRF token，不返回内部用户
ID、会话 token 或哈希。轮换时只保留当前和前一个 CSRF 哈希作为相邻标签页的短过渡窗口；密码修改、禁用
账号和注销仍会撤销会话。本地 developer 的商品集合查询强制绑定其 owner ID，items 与 total 都不跨租户。

自托管商品只能由本地管理员建档并绑定 active 本地 developer；Key 创建不会隐式生成无 owner 商品。
日志全文与字段查询最多覆盖 7 天，并受分页与数据库执行时间预算约束。
Key 列表同样使用有界游标分页，每个商品最多保留 64 枚 Key。本地账号连续失败 5 次后会在密码校验前
锁定 15 分钟；来源 IP 另有独立预算，避免分布式来源绕过账号维度限制或单一来源在线猜测多个账号。
所有 control 请求还在身份解析前执行来源预算。可选静态 Root Token 必须是 `rct_` 加 43 个 Base64URL
字符；服务会拒绝格式不符的配置，但无法从字符串本身证明熵，部署方必须用秘密管理系统从 32 字节安全
随机源生成。

新建公开 Key 的完整值使用 `REMOTE_KEY_ENCRYPTION_KEY` 做 AES-256-GCM 加密，密文绑定商品与 Key ID；
认证仍按独立的 `token_hash` 查库。该加密密钥必须由秘密管理系统持久保存，且不得与通知、Guard 或
Collector 密钥复用。密钥丢失或错误会使既有 Key 无法回看，但不会改变它们按哈希执行 ingest 认证的结果。
迁移前只保存哈希的旧 Key 无法逆向恢复，列表明确返回 `token_recoverable=false`，回看返回
`409 key_token_unavailable`，需要吊销并重建。数据库备份必须与对应密钥一起纳入恢复演练，但不能把密钥
写入数据库备份本身。

Issue 的持久标题只从结构化 Throwable 类型与首帧的受限 Java 标识符派生，不复制事件或异常 message。
普通 `admin` 的投影进一步把标题固定为 `Incident detected`、清空 fingerprint，并禁止用这些隐藏字段
搜索；Developer/Root 才能看到派生标题。

原始日志、Incident 正文和聚合/元数据的部署级保留期默认 7/30/90 天，可由本地管理员设置为 1..3650
天且必须单调不减。显式删除会在同一事务重算每日聚合和安装投影；Issue 仅保留明确标记的合规空壳，
正文、Incident 和安装明细不伪装成仍存在。部署方仍必须按自身合规要求设置数据库备份、访问审计和删除
流程；数据库备份不由保留 worker 代替治理。

## 自托管部署

在反向代理上终止 HTTPS，并只把可信代理 CIDR 配到 `REMOTE_TRUSTED_PROXY_CIDRS`；否则不要发送
`X-Forwarded-For`。服务端拒绝 `Forwarded` 和 `X-Real-IP`，只在直连对端属于可信 CIDR 时解析
`X-Forwarded-For`。

`REMOTE_DATABASE` 应使用权限最小化的专用 PostgreSQL 身份；不要与 Guard、业务插件或其他服务复用
数据库角色、密码或 `rpk_*` Key。公开 ingest 与受认证 control 建议使用不同的网络/代理规则，并限制
control 仅供管理员网络访问。可用 `REMOTE_STORAGE_MAX_BYTES` 设置部署级 PostgreSQL 实际容量上限；
达到上限或 `pg_database_size` 查询失败时 ingest 在读取压缩正文前 fail-closed，避免继续扩大存储。
`REMOTE_PUBLIC_ENDPOINT` 是显式部署事实，服务不会从可伪造的 Host 或内部监听地址推导公网接入地址。
`REMOTE_INGEST_PAUSE_REASON=telemetry_attestation_required` 是 Remote 自身的静态安全闸：它不查询 Guard，
也不把 Remote 的公开 Key 或安装标识提升为可信身份；ingest 与 control 从同一启动配置展示和执行该状态。
服务端实现和部署配置不属于本公共 Java 客户端仓库；部署方应以对应服务端版本的私有运维文档为准。

`GET /control/v1/system/build` 只允许本地 root/admin/developer，托管身份不可读取。注入的 version、commit
和 built_at 会成为运维可见元数据，不得把 Token、内部地址或其他秘密放进这些构建字符串。

通知 webhook 仅接受 HTTPS URL，禁止 URL 用户信息、fragment 和 IANA 特殊用途或其他非公网解析地址；发送器固定连接经验证
地址，保留原域 TLS SNI/Host，禁止重定向且不重新解析 hostname。邮件只发摘要，webhook 默认不含正文；
开发者显式选择携带正文时必须自行承担该目的地的访问与数据责任。outbox 的目标、URL 与 payload 使用
`REMOTE_NOTIFICATION_ENCRYPTION_KEY` 做 AES-256-GCM 加密，密钥必须由独立秘密管理提供且不得复用
Guard/Collector 密钥。

月度预算预警按商品与 UTC 月份只触发一次，事件数和逻辑字节任一首次跨阈值即可触发；预警只包含聚合
用量，不携带日志、Incident 或其他事件正文。订阅状态变更与 Incident 计费/入队共用商品行锁和同一事务
快照，避免暂停/恢复竞态绕过逻辑字节预算。

官方 BFF 使用独立 Ed25519 公钥验证的短时 token 接入 control。Remote 严格校验 `EdDSA`、`kid`、
`aud`、`sub`、角色、签发与过期时间，最大生命周期 15 分钟；这里只验证平台身份与 audience，不会把
Remote 客户端事件升级为可信来源。

协议字段、收据和限流语义见 [Remote 协议 v1](remote-protocol.md)。
