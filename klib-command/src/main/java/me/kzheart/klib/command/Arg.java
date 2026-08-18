package me.kzheart.klib.command;

import me.kzheart.klib.command.api.CommandArgument;
import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.List;

/**
 * {@link CommandArgument} 的唯一实现族，由 {@link Arguments} 工厂创建。
 *
 * <p>本类对外公开只是为了让调用方声明字段类型（如 {@code Arg<Integer> amount = ...}），
 * 构造器与解析方法都是包内可见，因此库外无法继承。扩展点是
 * {@link Arguments#custom(String, ArgumentParser, SuggestionProvider)}：
 * 传入自己的解析器与补全器即可获得一个可直接放进命令树的参数实例。
 *
 * <p>参数实例同时是 {@code context.get(arg)} 的身份键，必须保存并复用建树时的同一实例；
 * {@link Arguments#optional(Arg, Object)} 等包装工厂返回的是新实例，读取时应使用包装后的
 * 实例，或改用 {@code context.get(name, type)} 按名读取。
 */
public abstract class Arg<T> implements CommandArgument<T> {
    private final String name;
    private final boolean greedy;
    private final boolean optional;
    private final T defaultValue;

    Arg(String name, boolean greedy) {
        this(name, greedy, false, null);
    }

    Arg(String name, boolean greedy, boolean optional, T defaultValue) {
        this.name = CommandSpecImpl.requireSingleWord(name, "argument name");
        this.greedy = greedy;
        this.optional = optional;
        this.defaultValue = defaultValue;
    }

    @Override
    public final String name() {
        return name;
    }

    final boolean isGreedy() {
        return greedy;
    }

    /** 是否接受任意单个 token（string/greedy 等），用于检测被遮蔽的兄弟参数。 */
    boolean isCatchAll() {
        return greedy;
    }

    final boolean isOptional() {
        return optional;
    }

    final T defaultValue() {
        return defaultValue;
    }

    abstract T parse(String input, PlayerResolver players) throws ArgumentException;

    List<String> suggest(CommandSender sender, String prefix, PlayerResolver players) {
        return Collections.emptyList();
    }

    final String usage() {
        if (optional) {
            return "[" + name + "]";
        }
        return greedy ? "<" + name + "...>" : "<" + name + ">";
    }
}
