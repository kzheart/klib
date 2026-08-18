package me.kzheart.klib.command;

import me.kzheart.klib.command.api.CommandArgument;
import me.kzheart.klib.command.api.CommandContext;
import org.bukkit.command.CommandSender;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

final class CommandContextImpl implements CommandContext {
    private final CommandSender sender;
    private final String label;
    private final Map<CommandArgument<?>, Object> values;
    // 名称索引按解析顺序构建：同名参数出现在路径多层时，最深的一次覆盖较浅的一次。
    private final Map<String, Object> byName;

    CommandContextImpl(
            CommandSender sender,
            String label,
            Map<CommandArgument<?>, Object> values
    ) {
        this.sender = sender;
        this.label = label;
        this.values = new IdentityHashMap<CommandArgument<?>, Object>(values);
        Map<String, Object> named = new LinkedHashMap<String, Object>();
        for (Map.Entry<CommandArgument<?>, Object> entry : values.entrySet()) {
            named.put(entry.getKey().name(), entry.getValue());
        }
        this.byName = named;
    }

    @Override
    public CommandSender sender() {
        return sender;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public <T> T get(CommandArgument<T> argument) {
        if (argument == null) {
            throw new NullPointerException("argument");
        }
        if (!values.containsKey(argument)) {
            throw new IllegalArgumentException(
                    "argument was not parsed: " + argument.name()
                            + "；本次解析可用的参数名: " + byName.keySet()
                            + "；参数按对象身份读取，若使用 Arguments.optional(...) 等包装工厂，"
                            + "请用包装后的实例取值或改用 get(name, type) 按名取值");
        }
        @SuppressWarnings("unchecked")
        T value = (T) values.get(argument);
        return value;
    }

    @Override
    public Optional<Object> find(String name) {
        return Optional.ofNullable(name == null ? null : byName.get(name));
    }

    @Override
    public <T> T get(String name, Class<T> type) {
        if (type == null) {
            throw new NullPointerException("type");
        }
        if (name == null || !byName.containsKey(name)) {
            throw new IllegalArgumentException(
                    "argument was not parsed: " + name
                            + "；本次解析可用的参数名: " + byName.keySet());
        }
        return type.cast(byName.get(name));
    }
}
