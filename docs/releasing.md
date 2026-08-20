# Maven Central 发布

公共库的持续验证和正式发布使用两个独立工作流：

- `.github/workflows/ci.yml` 在每次分支 push 和 pull request 上执行完整测试、本地 Maven 发布，并分别
  构造普通 Klib 与 Guard API 的 unsigned Central bundle；它不读取发布 secret，也不调用 Central API；
- `.github/workflows/release-central.yml` 只接受与组件版本精确匹配的正式 tag，完成同一套测试后构造
  对应组件的 PGP 签名 bundle，通过 Central Portal Publisher API 上传，并选择 `AUTOMATIC` 发布。

普通 `main` push 不会进入发布工作流。普通模块和 Guard API 使用独立版本、tag 和 bundle，不会在升级
普通 Klib 时重复上传不可覆盖的旧 Guard API 版本。

## 组件与 Tag

| 组件 | 版本源 | 正式 tag | bundle |
| --- | --- | --- | --- |
| 普通 Klib 模块 | `klibVersion` | `klib-v<klibVersion>` | 只包含 19 个普通模块，不含 Guard API |
| Guard API | `klibGuardApiVersion` | `guard-api-v<klibGuardApiVersion>` | 只包含 `klib-guard-api` |

当前版本为 `klib:0.4.0` 与 `klib-guard-api:0.2.0`。两个组件独立发布，不能在发布
普通 Klib 时重复上传不可覆盖的 Guard API 版本。

## GitHub Environment 与 Secrets

在 GitHub 仓库建立名为 `maven-central` 的 Environment，建议配置 required reviewer，并把以下四项
设置为 Environment secrets：

| Secret | 内容 |
| --- | --- |
| `MAVEN_SIGNING_KEY` | ASCII-armored PGP 私钥 |
| `MAVEN_SIGNING_PASSWORD` | PGP 私钥密码 |
| `MAVEN_CENTER_USERNAME` | Central Portal 生成的 user token username |
| `MAVEN_CENTER_PASSWORD` | Central Portal 生成的 user token password |

工作流只把 secret 注入确实需要它的门禁、签名和上传步骤，不会传给 checkout、JDK 设置、Gradle 设置或
制品归档步骤，也不会输出其值。Central Publisher API 使用 `username:password` 的 Base64 结果作为
Bearer credential；这里的 token 不是 Central 网站登录密码。

## Tag 发布

发布 Guard API `0.2.0`：

```bash
git tag guard-api-v0.2.0
git push origin guard-api-v0.2.0
```

发布普通 Klib `0.4.0`：

```bash
git tag -a klib-v0.4.0 -m "release: 发布 Klib 0.4.0"
git push origin klib-v0.4.0
```

发布工作流会在第一次 Central 网络请求之前检查：

- ref 必须是 tag；
- tag 必须精确等于所选组件的版本 tag 并指向当前 `HEAD`；
- `klibVersion` 与 `klibGuardApiVersion` 必须是三段非 SNAPSHOT 版本；
- checkout 必须干净；
- 四个发布 secrets 必须全部存在；
- 最终 ZIP 只能包含所选组件。

任一条件不满足时不会调用 Central API。

## 安全的手动重跑

`workflow_dispatch` 只用于重跑同一个正式 tag。运行时必须：

1. `release_tag` 填写已存在的正式 tag；工作流会明确 checkout 该 tag，而不是运行入口所在的默认分支；
2. `component` 选择 `klib` 或 `guard-api`；
3. `version` 填写与该组件版本源完全一致的版本；
4. 勾选 `confirm_publish`；
5. 如果 `maven-central` Environment 配置了审批规则，通过对应审批。

手动输入不能绕过 ref、tag、组件、版本、签名或 secret 门禁。Maven Central 版本不可覆盖，已经成功上传
相同版本时不要重跑；需要修复时必须升级对应组件版本并创建新 tag。

## 本地验证

不使用发布 secret 的完整验证会同时产生两个隔离的 unsigned bundle：

```bash
./gradlew clean check publishToMavenLocal centralDryRunBundle --no-configuration-cache
```

正式签名 bundle 任务还会执行 tag、组件、干净 checkout 和 secret 门禁：

```bash
./gradlew prepareKlibCentralBundle \
  -PreleaseComponent=klib \
  -PreleaseTag=klib-v<klib-version> \
  --no-configuration-cache

./gradlew prepareGuardApiCentralBundle \
  -PreleaseComponent=guard-api \
  -PreleaseTag=guard-api-v<guard-api-version> \
  --no-configuration-cache
```

上传实现遵循 Sonatype 的
[Portal Publisher API](https://central.sonatype.org/publish/publish-portal-api/)：向
`POST https://central.sonatype.com/api/v1/publisher/upload` 发送名为 `bundle` 的 multipart 文件，并使用
`publishingType=AUTOMATIC`；随后通过 `POST /api/v1/publisher/status?id=<deploymentId>` 等待
`PUBLISHED` 或报告 `FAILED`。

## Linux CI 的安全临时目录

Remote 磁盘队列会检查整条祖先目录链，拒绝位于 group/other 可写祖先下的敏感队列目录。GitHub Hosted
Runner 的默认测试临时目录存在可写祖先，权限不满足这一约束。CI 因此在 `$HOME` 下创建
`0700` 的 `.klib-ci-tmp` 并通过 `-Djava.io.tmpdir` 交给测试 JVM。安全检查和相关测试仍完整执行；没有
禁用测试，也没有放宽生产目录的 owner-only 与抗替换语义。
