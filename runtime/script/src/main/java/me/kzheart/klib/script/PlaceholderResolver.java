package me.kzheart.klib.script;

/** papi 动作使用的可选占位符集成。 */
@FunctionalInterface
public interface PlaceholderResolver {

    String resolve(Object sender, String text);
}
