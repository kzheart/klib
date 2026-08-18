# Remote 协议 v1

本篇是 `/ingest/v1` 的线协议事实源。它独立于 Guard；不存在 `/api` 兼容路径，也不接受旧 `pk_` Key。
服务端与 Java 客户端都严格处理 schema v1。

## 鉴权与路由

请求使用 `Authorization: Bearer rpk_live_...` 或 `Bearer rpk_test_...`。Key 的格式为
`rpk_(live|test)_[A-Za-z0-9_-]{43}`，是公开、只写项目标识，不是秘密或客户端真实性凭据。

| 方法 | 路径 | 成功 |
| --- | --- | --- |
| `GET` | `/ingest/v1/settings` | `200` settings JSON |
| `POST` | `/ingest/v1/batches` | `202` 逐事件 receipt JSON |

其他方法对上述精确路径返回 `405 {"error":"method_not_allowed"}`；其他路径返回
`404 {"error":"not_found"}`。响应是 `application/json` 且 `Cache-Control: no-store`。两个公开端点
都在查询 Key 或读取 batch body 前执行持久化的来源 IP 与 Key selector 请求预算；格式无效或不存在的
Key 也受来源预算约束。

## Settings

`GET /ingest/v1/settings` 返回：

```json
{
  "schema_version": 1,
  "key_status": "active",
  "environment": "production",
  "accepting_events": true,
  "policy": {
    "paused": false,
    "exceptions": true,
    "logs": true,
    "manual_incidents": false,
    "minimum_level": "info",
    "sample_rate": 100
  },
  "limits": { "max_batch_events": 100 },
  "retention": { "logs_days": 7, "incidents_days": 30, "aggregates_days": 90 }
}
```

settings 顶层、`policy`、`limits` 与 `retention` 均为完整必填对象，不接受未知或重复字段、尾随 JSON、
错层字段或类型替换。`sample_rate` 必须为 0–100 的整数，保留期由服务端部署配置提供，三项各为
1..3650 天且满足 `logs_days <= incidents_days <= aggregates_days`。实际 `limits` 还必须包含
`max_compressed_bytes`、`max_decompressed_bytes`、`max_event_bytes`、
`max_message_bytes`、`max_attributes`、`max_installation_id_bytes`、`max_event_id_bytes`、
`max_attribute_key_bytes`、`max_attribute_value_bytes`、`max_environment_field_bytes` 以及每分钟的
`key_events_per_minute`、`ip_events_per_minute`、`installation_events_per_minute`、
`asn_events_per_minute`、`product_spike_events_per_minute`。客户端把 policy 和其构建能力取交集；
`accepting_events=false` 时须 fail-closed。

部署方可用 Remote 自身的全局安全闸临时暂停写入。此时 settings 保持 `200`，但
`accepting_events=false`；batch 在读取压缩正文前返回 `403 telemetry_attestation_required`。该状态只表示
Remote 部署策略，不读取或引用 Guard 身份、授权或风控数据。

## Batch 与事件

`POST /ingest/v1/batches` 必须为 `Content-Type: application/json`（可带参数）且
`Content-Encoding: gzip`。解压后的 JSON 必须只有一个值，不接受未知字段。

```json
{
  "schema_version": 1,
  "installation_id": "inst_0123456789abcdef0123456789abcdef",
  "environment": {
    "plugin_version": "2.4.0",
    "minecraft": "Paper 1.21.4",
    "java": "17",
    "os": "Linux"
  },
  "events": [
    {
      "event_id": "evt_example",
      "type": "log",
      "occurred_at": "2026-08-14T00:00:00Z",
      "level": "warn",
      "logger": "example.market",
      "message": "listing delayed",
      "tags": { "listing": "true" },
      "attributes": { "listing_count": "42" },
      "payload": { "context": {}, "mdc": {}, "tags": [] }
    }
  ]
}
```

`installation_id` 与 `event_id` 必须非空、仅含 `[A-Za-z0-9._:-]`，并受 settings 的字节限制；非空
`operation_id` 使用同一字符集且最多 256 字节。四个环境字段均不能为空。事件共同字段是 `event_id`、
`type`、`occurred_at`，可选公共字段包括 `operation_id`、`tags`、
`attributes`（字符串 map）和对象形态的 `payload`。

- `log`：还须 `level`（`trace`/`debug`/`info`/`warn`/`error`）、`logger`、`message`。服务端还会检查策略
  的等级和采样。
- `incident`：还须 `message`、最多 256 字节的 `fingerprint`、以及 `source`（`automatic` 或 `manual`）。
  `automatic` 受 exceptions 策略控制，`manual` 受 manual incidents 策略控制。

Java SDK 在事件顶层写 `operation_id`，并在 `payload.operation` 写完整 operation 节点。节点的 `id` 必须与
顶层 `operation_id` 一致，直接父节点使用 `parent_id`；节点自身和 `ancestors` 中的每一层都可带
`duration_ms`。该值由客户端以本地单调时钟计算结束时间减开始时间，单位毫秒；未结束为 `null`，旧客户端
也可以完全不带此字段。它是 payload 的可选扩展，不改变 schema v1，服务端按原始 JSON 透传。服务端只把
ID 一致且格式有效的直接 `parent_id` 提取为显式 operation 关系，不从 operation 名称、阶段或耗时推断关系。

客户端要声明确定性失败原因时，Incident 可带唯一的显式 marker：

```json
{
  "payload": {
    "confirmed_cause": {
      "target_type": "validator",
      "target_id": "config-schema",
      "summary": "Schema validator rejected field mode"
    }
  }
}
```

三个字符串都必须非空；`target_type` 使用小写 snake_case，`target_id` 使用事件 ID 相同的 opaque 字符集。
该 marker 是公开写入客户端作出的声明，不是服务端验证过的真实性证明。Throwable、message、operation
outcome、Contributor 结果以及时间或文本相似度都不会自动生成 `confirmed_cause`。

默认服务端限制为压缩 256 KiB、解压 1 MiB、每批 100 事件、单事件 64 KiB、message 32 KiB、attributes
32 项、安装 / event ID 128 字节、attribute key 64 字节、attribute value 1024 字节、环境字段 128 字节。
以 settings 中的实际限制为准。

## 确定性日志采样

Incident 不采样。对日志，采样率 `r` 为 0–100；`r=0` 全拒绝，`r=100` 全接受。其余情况：

```text
D = SHA-256(UTF-8("klib-remote-sample-v1\\0") || UTF-8(event_id))
value = uint32_big_endian(D[0..3])
accept = (value mod 100) < r
```

这是跨语言确定性规则；客户端与服务端使用同一算法。示例向量：`evt-a` 在 99 时拒绝、100 时接受；
`evt-b` 在 1 时拒绝、2 时接受；`evt-0001` 在 46 时拒绝、47 时接受。

## Receipt、幂等与错误

成功 batch 返回完整、按请求索引一一对应的结果：

```json
{
  "results": [
    { "index": 0, "event_id": "evt_example", "status": "accepted" }
  ],
  "accepted": 1,
  "duplicate": 0,
  "rejected": 0
}
```

`accepted` 是首次写入；`duplicate` 是相同 Key + `event_id` 且安装、环境和规范化事件正文均相同；
二者不得带 `error`。`rejected` 是永久事件级拒绝，必须附非空 `error`（例如 `sampled_out`、`capability_disabled`、`invalid_log`、`invalid_operation_id`、
`event_id_conflict`）。相同 Key + `event_id` 但任一受保护内容不同是 `rejected/event_id_conflict`，不是重复。
客户端只有拿到严格且完整的 receipt 才能删队列项：顶层和逐项都不接受未知字段、重复字段、类型错误或
尾随 JSON；索引、`event_id`、状态与三个计数必须和请求一一对应且一致。

请求级错误为 `{ "error": "..." }`：无效/吊销 Key 为 `401 invalid_key`；暂停 Key、商品或商品策略分别为
`403 key_paused`、`403 product_paused`、`403 policy_paused`；全局安全闸为
`403 telemetry_attestation_required`。缺 JSON 或 gzip 分别为 `415 json_required` / `415 gzip_required`，无效 JSON 与字段为
`400`，请求或 batch 过大为 `413`，请求级、事件级限流或月度预算耗尽为 `429` 并带 `Retry-After`，存储、ASN 或限流器
故障为 `503`。`429` 是暂时错误；客户端应按 `Retry-After` 重试。完整错误处理见
[klib-remote · 异步交付、队列与错误](modules/remote.md#异步交付队列与错误)。
