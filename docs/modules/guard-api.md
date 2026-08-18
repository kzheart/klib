# klib-guard-api

状态：受保护商品的公开编译期契约
坐标：`me.kzheart.klib:klib-guard-api:<guard-version>`
许可证：Apache License 2.0

`klib-guard-api` 只定义 Guard 门户与受保护商品之间的生命周期边界。商品使用它完成编译，真正运行时
由服务器上的 `KlibGuard.jar` 提供这些类；本模块不包含授权协议、JNI、Native、下载器或任何生产密钥。
本模块采用仓库根目录的 Apache License 2.0；Guard runtime 与 Native 在私有仓库独立维护和授权。

## 适用场景

- 编写由 KlibGuard 动态加载的受保护商品；
- 继承 `KlibCloudPlugin` 使用商品级数据目录、日志和根 `Scope`；
- 直接实现 `RemotePluginEntrypoint` 接管完整生命周期。

普通 Bukkit/Paper 插件不需要本模块，应继续使用
[Klib Gradle 插件](https://github.com/kzheart/klib-gradle-plugin)。

## 接入

商品必须把 Guard API 保持为 `compileOnly`，并按目标服务端声明 Bukkit/Paper API：

```kotlin
dependencies {
    compileOnly("me.kzheart.klib:klib-guard-api:<guard-version>")
    compileOnly("org.spigotmc:spigot-api:1.12.2-R0.1-20180712.012057-156") {
        isTransitive = false
    }
}
```

`klib-guard-api` 传递暴露编译所需的 `klib-core` API。不要把 Guard API 或 Core 打进、重定位进商品
JAR，否则商品类与父加载器中的生命周期类型会产生不同的类身份。

## 快速开始

```java
package com.example.cloud;

import me.kzheart.klib.guard.KlibCloudPlugin;
import me.kzheart.klib.scope.Scope;

public final class CloudExample extends KlibCloudPlugin {
    @Override
    protected void setup(Scope root) {
        logger().info("正在启用 " + host().productId());
        root.install(() -> logger().info("商品资源已释放"));
    }
}
```

商品 JAR 还必须包含 `META-INF/klib-guard/entrypoint`：

```text
com.example.cloud.CloudExample
```

完整打包、签名和交付规则由 Guard runtime 的私有接入文档定义；公开 API 只承诺本页列出的生命周期契约。

## 主要类型

| 类型 | 用途 |
| --- | --- |
| `KlibCloudPlugin` | 推荐的商品生命周期基类，负责创建和关闭根 `Scope` |
| `RemotePluginEntrypoint` | Guard 调用的底层 load、enable、disable 契约 |
| `PluginHost` | 提供商品 ID、门户插件、服务端、数据目录和日志器 |

`KlibCloudPlugin` 的 `load()` 适合读取轻量配置，`setup(Scope)` 负责安装监听器、任务和存储资源，
`disable()` 只处理无法归入 Scope 的最后清理。不要直接调用 `onLoad`、`onEnable` 或 `onDisable`。

## 生命周期、线程与版本约束

- API 与商品源码均保持 Java 8 语法/API；
- Bukkit 状态只能在主线程访问，异步结果必须通过 `SchedulerFactory` 或 Bukkit 调度器切回主线程；
- 商品拥有独立数据目录和根 `Scope`，关闭时资源按 Scope 逆序释放；
- Guard API 版本跟随 `KlibGuard.jar`，不跟随普通 Klib 模块或 Klib Gradle 插件版本；
- API 只承诺本页列出的生命周期类型，Guard runtime 中的协议和 JNI 类型不是商品 API。

## 相关模块

- [klib-core](core.md)：`Scope`、调度、事件与日志能力；
- [Klib Gradle 插件](https://github.com/kzheart/klib-gradle-plugin)：公共插件的构建与模块选择。
