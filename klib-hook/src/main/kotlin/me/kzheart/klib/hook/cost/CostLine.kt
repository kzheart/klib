package me.kzheart.klib.hook.cost

/** 某项消耗的描述及玩家当前是否满足，用于渲染 lore 或提示消息。 */
class CostLine internal constructor(private val description: String, private val satisfied: Boolean,
                                     private val error: String?) {
    fun description(): String = description
    fun satisfied(): Boolean = satisfied
    fun error(): String? = error
    override fun toString(): String = (if (satisfied) "✔ " else "✘ ") + description
}
