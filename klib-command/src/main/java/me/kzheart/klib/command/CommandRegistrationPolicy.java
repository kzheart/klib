package me.kzheart.klib.command;

/**
 * 插件启动注册时的裸标签冲突策略。
 *
 * <p>只决定根名与别名的裸标签归属，不授权覆盖其他插件的命名空间，也不提供运行时
 * 重新注册或关闭后的标签恢复栈。同一插件不得将相同标签交给多个注册器管理。</p>
 */
public enum CommandRegistrationPolicy {
    /** 保留已有绑定并拒绝冲突的根名或别名；默认策略。 */
    REJECT,
    /** 仅在实际注册时接管裸标签；关闭只保证逻辑停用，不承诺恢复此前的绑定。 */
    REPLACE_UNQUALIFIED
}
