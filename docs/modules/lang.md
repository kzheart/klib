# Lang 模块

状态：稳定公开模块
模块名：`lang`
制品：`me.kzheart.klib:klib-lang`

`klib-lang` 提供可重载的 YAML 消息目录、MiniMessage 与旧式颜色解析、安全命名占位符，以及面向聊天栏、Action Bar、Title 和 Boss Bar 的 Bukkit 路由。

## 何时使用

适合以下场景：

- 把玩家可见文本从业务代码中移出，并允许服主修改；
- 同时兼容 Java 8 / Spigot 1.12.2 与较新服务端的富文本发送；
- 统一处理前缀、命名占位符和可选外部占位符；
- 让命令错误、帮助与业务消息共享同一套语言文件；
- 需要按消息选择聊天栏、Action Bar、Title 或 Boss Bar。

## 接入

推荐通过 Klib Gradle 插件选择模块：

```kotlin
klib {
    modules {
        lang()
    }
}
```

`lang` 会自动带入 `core` 和 `config`。

直接依赖的高级用法：

```kotlin
dependencies {
    implementation("me.kzheart.klib:klib-lang:<klib-version>")
}
```

直接依赖时需自行打包和重定位 Klib、配置实现与 MiniMessage 运行时。

## 快速开始

在资源目录提供语言文件：

```yaml
# src/main/resources/lang/zh_CN.yml
messages:
  common:
    prefix: "<gray>[<gold>MyPlugin</gold>]</gray> "
  welcome: "{prefix}<green>欢迎 {player}！</green>"
  reward: "actionbar: <yellow>获得 {amount} 枚金币</yellow>"
  announcement:
    - "<gold>服务器公告</gold>"
    - "<gray>祝你游戏愉快</gray>"
```

安装运行时并发送消息：

```java
@Override
protected void setup(Scope root) {
    LangRuntime lang = LangModule.install(
            root,
            getServer(),
            getDataFolder().toPath(),
            getClassLoader(),
            "zh_CN",
            null);

    root.on(PlayerJoinEvent.class, event -> lang.send(
            event.getPlayer(),
            "welcome",
            Placeholders.of("player", event.getPlayer().getName())));
}
```

首次启动会把资源文件提取到插件数据目录的 `lang/zh_CN.yml`。之后可通过 `lang.configDocument().reload()` 或文件监听更新消息，无需重建 `LangRuntime`。

`install` 除了返回运行时，还会把它注册为作用域能力，因此不需要在各处传递引用：

```java
LangRuntime lang = root.requireCapability(LangRuntime.class);
```

能力遵循作用域的常规查找规则，子作用域可以直接取到父作用域安装的运行时。同一作用域安装多个地区实例时，能力指向首个安装的运行时，其余实例仍以返回值使用。

## 语言文件与回退

业务消息通常放在根节点 `messages` 下。缺少 `messages` 时自定义消息目录为空，并继续回退到内置消息；根节点存在但类型错误时才会加载失败。嵌套配置节会展开为点分键，例如：

```yaml
messages:
  arena:
    joined: "<green>已加入竞技场</green>"
```

对应键为 `arena.joined`。

地区名会规范化，例如 `zh-cn` 变为 `zh_CN`。如果请求的地区资源不存在，整个运行时回退到内置默认地区 `zh_CN`，并只创建 `lang/zh_CN.yml`；不会把中文默认内容写入一个不存在的其他地区文件。发生这种回退时，会通过 `java.util.logging` 记录一条 `WARNING`，写明请求的地区和实际回退到的地区，便于发现拼错或未打包的语言资源。

用户语言文件缺少 Klib 内置消息键时，会回退到内置目录。业务自定义键没有回退值时，渲染结果为红色的 `[missing:消息键]`，并对该键记录一次警告。

## 消息格式

### MiniMessage 与旧式颜色

字符串消息支持 MiniMessage，并兼容常用 `&` 颜色和格式码：

```yaml
messages:
  modern: "<gradient:#ff0000:#0000ff><bold>渐变标题</bold></gradient>"
  legacy: "&a旧式绿色 &l粗体"
  clickable: "<click:run_command:/shop><hover:show_text:'打开商店'>点击打开商店</hover></click>"
```

较旧服务端无法表达的 RGB 色会降级为最接近的传统颜色。玩家聊天与 Action Bar 会优先尝试富组件发送，失败时回退到 legacy 文本；控制台和非玩家接收者始终收到无颜色、无点击和无悬停元数据的纯文本。

### 多行消息

YAML 字符串列表会以换行连接：

```yaml
messages:
  rules:
    - "第一条规则"
    - "第二条规则"
```

### 结构化富文本条目

需要用 YAML 字段描述单段富文本时，可以使用含字符串 `text` 的配置节：

```yaml
messages:
  docs:
    text: "查看项目文档"
    color: gold
    bold: true
    hover: "点击打开"
    click:
      type: open_url
      value: "https://example.com/docs"
```

结构化条目适合单一文本片段；多段嵌套样式优先使用 MiniMessage。

## 占位符

命名占位符使用 `{name}`：

```java
lang.send(
        player,
        "reward",
        Placeholders.of(
                "amount", Integer.valueOf(10),
                "reason", "daily"));
```

`Placeholders.of` 接受成对的“键、值”，返回只读映射。也可以传普通 `Map<String, ?>`。

`{prefix}` 是保留占位符，只能来自语言目录中的 `common.prefix`，调用方传入同名值不会覆盖它。

消息管线顺序固定为：

1. 查找消息键并提取路由前缀；
2. 解析模板的 MiniMessage，再把 `{prefix}` 替换为同样经 MiniMessage 解析的前缀片段；
3. 如果提供了 `PlaceholderApi`，对模板文本执行外部占位符展开；
4. 把 `{name}` 形式的调用方值作为字面文本插入；
5. 路由到接收者。

调用方的命名值不会再次进入 MiniMessage、路由或外部占位符解析。因此玩家输入即使包含 `<click:...>`、`actionbar:` 或 `%some_placeholder%`，也只会作为文字显示，不能注入样式和操作。

需要接入外部占位符系统时，在安装模块时传入自己的 `PlaceholderApi` 适配器；不需要时传 `null`。外部展开只处理语言文件模板，不处理之后插入的命名值。

## 消息路由

在消息开头使用路由前缀：

```yaml
messages:
  chat-message: "chat: 普通聊天消息"
  progress: "actionbar: <green>进度 {current}/{total}</green>"
  completed: "title: <gold>完成！</gold>|<gray>奖励已发放</gray>"
  warning: "bossbar: <red>区域即将关闭</red>"
```

- `chat:`：聊天栏，也是没有前缀时的默认路由；
- `actionbar:`：Action Bar；
- `title:`：Title，第一个 `|` 之后作为副标题；
- `bossbar:`：白色实心 Boss Bar，默认显示 100 tick。

也可以在代码中覆盖目录声明的路由：

```java
lang.send(
        MessageRecipient.commandSender(player),
        MessageRoute.ACTION_BAR,
        "progress",
        Placeholders.of("current", 3, "total", 10));
```

Boss Bar 在同一玩家收到新 Boss Bar 时会替换旧实例；有 Core 调度能力时会自动到期。作用域关闭会移除所有仍在显示的 Boss Bar。

## 只渲染，不发送

适配其他输出系统时，可以只获取解析结果：

```java
RichText text = lang.pipeline().render(
        MessageRecipient.commandSender(sender),
        "welcome",
        Placeholders.of("player", sender.getName()));

String plain = text.plainText();
String legacy = text.legacyText();
```

`RichText` 与 `RichTextSegment` 不向业务 API 暴露 Adventure 类型，便于跨服务端版本使用。

## 与命令模块共用消息

安装 Command 时传入同一个消息管线，命令错误、帮助和内置子命令就会从 `messages.command.*` 读取：

```java
LangRuntime lang = LangModule.install(
        root,
        getServer(),
        getDataFolder().toPath(),
        getClassLoader(),
        "zh_CN",
        null);

CommandModule.install(
        root,
        BukkitCommandRegistrar.discover("myplugin"),
        lang.pipeline());
```

缺少命令消息时仍会回退到 Klib 内置中英文目录。

## 生命周期与线程约束

- 语言配置文档、路由器、Boss Bar 和重载监听器都属于安装时传入的作用域。
- 作用域关闭后，`LangRuntime` 的目录、管线和发送入口不可继续使用。
- 在 `KPlugin` 中，语言文件变更会通过 Core 调度到主线程后替换目录与前缀。
- Bukkit 的聊天、Action Bar、Title 和 Boss Bar API 应在主线程调用。不要从任意异步回调直接调用 `lang.send`；使用 `thenSync` 或同步调度器切回主线程。
- 消息目录替换是完整快照替换；正在发送的消息使用本次解析得到的稳定结果。

## 注意事项

- `messages` 下某个配置节只要拥有字符串 `text` 子项，就会被视为结构化富文本，而不是普通嵌套命名空间。
- 路由前缀只在消息最开头识别，不区分大小写；前缀后的一个空格会被移除。
- 对控制台发送 Title、Action Bar 或 Boss Bar 会降级为纯文本，不会创建对应 Bukkit UI。
- `lang.configDocument().reload()` 的监听语义与 Config 模块一致；需要等待监听器完成时使用 `reloadAsync()`。
- 不要把用户输入直接拼接进语言模板字符串；把它作为命名占位符值传入，才能保留字面插入的安全边界。

## 相关模块

- [Core](core.md)：作用域、同步调度与资源清理。
- [Config](config.md)：语言文件提取、监听和原子重载。
- [Command](command.md)：复用语言管线显示错误与帮助。
- [Hook](hook.md)：PlaceholderAPI 等可选插件集成。
- 完整接线方式见本页“快速开始”和“完整示例”。
