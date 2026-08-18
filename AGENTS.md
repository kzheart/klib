# Klib 公共仓库协作指南

## 沟通与边界

- 始终使用简体中文沟通；代码标识、命令和配置键保留原文。
- 本仓库只维护 Apache-2.0 公共 Java 模块和 `klib-guard-api`。
- 禁止加入 Guard runtime、Native、Collector、部署配置、生产密钥或授权内部文档。
- Gradle 插件在 `kzheart/klib-gradle-plugin` 独立维护，不在这里复制源码。

## 工程约束

- 所有公共 Java 产物必须保持 Java 8 字节码以及 Java 8 语法/API 边界。
- 实现事实以当前源码、测试和构建配置为准；公开行为变化必须同步更新对应模块文档。
- `klib-compat-v*` 是兼容实现，使用者通过 `klib-compat` 接入。
- `klib-guard-api` 只包含公开生命周期契约，不得引入协议、JNI 或运行时实现。
- 不新增未要求的旧 API 别名、降级路径或兼容层。

## 验证

```bash
./gradlew clean check publishToMavenLocal --no-configuration-cache
./gradlew centralDryRunBundle --no-configuration-cache
```

修改文档后检查相对链接；修改公共 API、依赖、版本或发布行为时，同步更新根 README 与模块文档。

## Git

- 提交信息使用 Conventional Commits：`type(scope): 中文描述`。
- `type` 仅使用 `feat`、`fix`、`test`、`docs`、`refactor`、`perf`、`chore`、`build`、`ci`、
  `style`、`revert`。
- 保留用户已有改动，不覆盖或回滚无关文件。
