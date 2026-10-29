# Maven 仓库发布

公共模块发布到 `https://maven.kzheart.me/releases`，匿名下载、专用令牌上传。
Gradle 插件仍通过 Gradle Plugin Portal 发布；历史 Maven Central 版本继续保留。

## 验证与发布

- `.github/workflows/ci.yml` 在分支 push 和 pull request 上执行完整测试、本地 Maven 发布及
  `releaseDryRunBundle`，不读取发布凭据。
- `.github/workflows/release-maven.yml` 只接受与组件版本精确匹配的正式 tag，完成完整测试后生成
  PGP 签名 ZIP，由 `scripts/publish-maven.py` 上传并逐文件回读比对完整字节。

| 组件 | 版本源 | 正式 tag | ZIP 范围 |
| --- | --- | --- | --- |
| 普通模块 | `klibVersion` | `klib-v<klib-version>` | 模块清单中除 Guard API 外的模块 |
| Guard API | `klibGuardApiVersion` | `guard-api-v<guard-api-version>` | 只含 `klib-guard-api` |

普通 `main` push 不发布。组件独立发布，升级普通模块不会重复上传旧 Guard API。
更新对应组件版本和 CHANGELOG，提交并推送后再创建 tag：

```bash
git tag -a klib-v<klib-version> -m "release: 发布 Klib <klib-version>"
git push origin klib-v<klib-version>
# Guard API 独立使用 guard-api-v<guard-api-version>
```

## 凭据与门禁

发布 job 使用 `maven-repository` Environment；以下四项配置在仓库或该 Environment 的 Secrets 中：

| Secret | 内容 |
| --- | --- |
| `MAVEN_SIGNING_KEY` | ASCII-armored PGP 私钥 |
| `MAVEN_SIGNING_PASSWORD` | PGP 私钥密码 |
| `MAVEN_REPOSITORY_USERNAME` | 仅允许写入 Klib 命名空间的发布令牌名 |
| `MAVEN_REPOSITORY_PASSWORD` | 发布令牌 secret |

凭据仅注入门禁、签名或上传步骤，不进入命令参数、Git、日志或归档。上传使用 HTTPS，拒绝重定向，
不把 Authorization 发给其他站点。URL 可由 `MAVEN_REPOSITORY_URL` 指定；正式工作流固定为上述地址。

任何远端写入之前必须验证：tag 与组件版本精确匹配并指向 HEAD、checkout 干净、版本为三段非 SNAPSHOT、
签名及发布凭据存在、ZIP 仅包含所选组件，且必需 JAR、POM、签名和校验文件完整。

## 发布中断与不可覆盖

上传程序先核对整个 ZIP 的所有远端文件；发现不同字节立即停止，尚未执行任何写入。
相同文件跳过，缺失文件依次上传，全部 payload 就绪后才上传 POM，并逐项回读，最后更新 Maven 版本元数据。发布 job 串行运行，避免元数据并发丢失。
响应丢失时先核对远端字节，不盲目重放上传；相同 tag 可用于续传相同字节。

标准 Maven 文件上传不提供 Central 的整包原子可见性；中断可能留下尚无 POM 的文件。
不能删除、覆盖或重建成不同字节；应使用原签名 ZIP 续传。已完成版本不得覆盖，修复须发新版本。

手动重跑选择 `release-maven`，填写原 `release_tag`、匹配的 `component` / `version` 并勾选
`confirm_publish`。工作流 checkout 原 tag，不绕过任何门禁；如原构建无法复现同一字节，
下载原 workflow 保留的签名 ZIP，用上传脚本续传。制品默认保存 30 天。

## 本地验证

```bash
./gradlew clean check publishToMavenLocal releaseDryRunBundle --no-configuration-cache
python3 -m unittest discover -s scripts/tests -v
```

正式签名任务为 `prepareKlibReleaseBundle`、`prepareGuardApiReleaseBundle`，要求传入
`-PreleaseComponent=klib|guard-api` 与 `-PreleaseTag=<对应正式tag>` 并满足全部门禁。

Linux CI 在 `$HOME/.klib-ci-tmp` 创建 `0700` 临时目录，以 `-Djava.io.tmpdir` 传给测试 JVM。
Remote 队列仍执行 owner-only 与抗替换检查，不使用 runner 默认可写祖先，也不放宽生产检查。
