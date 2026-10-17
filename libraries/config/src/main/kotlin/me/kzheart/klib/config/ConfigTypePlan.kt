package me.kzheart.klib.config

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import me.kzheart.klib.config.annotation.Comment
import me.kzheart.klib.config.annotation.Key
import me.kzheart.klib.config.annotation.Range

/** One immutable declaration plan shared by mapping and explicit default generation. */
internal class ConfigTypePlan private constructor(@JvmField val type: Class<*>) {
    @JvmField val fields: List<FieldPlan>
    @JvmField val declaredNames: Set<String>
    @JvmField val kotlin: KotlinConfigPlan?

    init {
        val hierarchy = generateSequence(type) { it.superclass }
            .takeWhile { it != Any::class.java }.toList().asReversed()
        val names = linkedSetOf<String>()
        val mappedNames = hashSetOf<String>()
        val declarations = hierarchy.flatMap { owner ->
            owner.declaredFields.map { field ->
                FieldPlan(field).also { plan ->
                    if (!plan.ignored && (plan.name.trim().isEmpty() || !mappedNames.add(plan.name))) {
                        throw ConfigException("blank or duplicate field key: ${plan.name}")
                    }
                    // Ignored declarations suppress warnings without shadowing mapped keys.
                    names.add(plan.name)
                }
            }
        }
        fields = Collections.unmodifiableList(declarations)
        kotlin = if (type.isAnnotationPresent(Metadata::class.java)) KotlinConfigPlan(type, fields, names) else null
        declaredNames = Collections.unmodifiableSet(names)
    }

    fun defaults(): Any = kotlin?.defaults() ?: try {
        type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
    } catch (failure: ReflectiveOperationException) {
        throw ConfigException("Cannot create defaults for ${type.name}", failure)
    }

    class FieldPlan(@JvmField val field: Field) {
        @JvmField val name: String = field.getAnnotation(Key::class.java)?.value ?: field.name
        @JvmField val ignored: Boolean = Modifier.isStatic(field.modifiers) ||
            Modifier.isTransient(field.modifiers) || field.isSynthetic
        @JvmField val comment: Comment? = field.getAnnotation(Comment::class.java)
        @JvmField val range: Range? = field.getAnnotation(Range::class.java)
    }

    companion object {
        private val plans = object : ClassValue<ConfigTypePlan>() {
            override fun computeValue(type: Class<*>): ConfigTypePlan = ConfigTypePlan(type)
        }
        @JvmStatic fun of(type: Class<*>): ConfigTypePlan = plans.get(type)
    }
}
