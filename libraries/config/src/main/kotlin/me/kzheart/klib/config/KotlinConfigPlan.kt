package me.kzheart.klib.config

import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaType

/** Kotlin constructor metadata used by the module's shared mapping plan. */
internal class KotlinConfigPlan(
    private val type: Class<*>,
    fields: List<ConfigTypePlan.FieldPlan>,
    declaredNames: MutableSet<String>,
) {
    private val constructor: KFunction<Any>
    private val parameters: List<ParameterPlan>
    private val constructorFields: Set<Field>
    private val propertyTypes: Map<Field, KType>

    init {
        val kotlinType = type.kotlin
        if (kotlinType.isInner || kotlinType.objectInstance != null) {
            throw ConfigException("Kotlin config ${type.name} must be an ordinary, non-inner class")
        }
        constructor = kotlinType.primaryConstructor
            ?: throw ConfigException("Kotlin config ${type.name} requires a primary constructor")
        constructor.isAccessible = true
        val fieldsByName = fields.associateBy { it.field.name }
        parameters = constructor.parameters.map { parameter ->
            if (parameter.kind != KParameter.Kind.VALUE || parameter.isVararg) {
                throw ConfigException("Kotlin config ${type.name} does not support receiver or vararg constructor parameters")
            }
            val name = parameter.name
                ?: throw ConfigException("Unnamed Kotlin constructor parameter in ${type.name}")
            val field = fieldsByName[name]
            val key = field?.name ?: name
            if (field == null && !declaredNames.add(key)) {
                throw ConfigException("blank or duplicate constructor key: $key")
            }
            if (field?.ignored == true && !parameter.isOptional) {
                throw ConfigException("Ignored Kotlin constructor parameter $key requires a default value")
            }
            ParameterPlan(parameter, key, field)
        }
        constructorFields = parameters.mapNotNull { it.field?.field }.toSet()
        propertyTypes = kotlinType.memberProperties.mapNotNull { property ->
            property.javaField?.takeIf { it.declaringClass.isAnnotationPresent(Metadata::class.java) }
                ?.let { it to property.returnType }
        }.toMap()
    }

    fun construct(mapper: YamlConfigMapper, node: ConfigNode): Any {
        val arguments = linkedMapOf<KParameter, Any?>()
        for (plan in parameters) {
            if (plan.field?.ignored == true) continue
            val child = node.child(plan.key)
            if (!child.exists()) {
                if (plan.parameter.isOptional) continue
                throw child.mappingError("missing required Kotlin constructor parameter ${plan.parameter.name}")
            }
            arguments[plan.parameter] = convert(mapper, child, plan.parameter.type)
        }
        return invoke(arguments) { message, cause -> node.mappingError(message, cause) }
    }

    fun defaults(): Any {
        val required = parameters.firstOrNull { !it.parameter.isOptional }
        if (required != null) {
            throw ConfigException("Cannot create defaults for ${type.name}: required constructor parameter ${required.key} has no default")
        }
        return invoke(emptyMap()) { message, cause -> ConfigException(message, cause) }
    }

    fun isConstructorField(field: Field): Boolean = field in constructorFields

    fun convertField(mapper: YamlConfigMapper, node: ConfigNode, field: Field): Any? {
        val kotlinType = propertyTypes[field]
        return if (kotlinType == null) mapper.convert(node, field.genericType)
        else convert(mapper, node, kotlinType)
    }

    fun validateField(instance: Any, node: ConfigNode, field: Field) {
        val kotlinType = propertyTypes[field] ?: return
        field.isAccessible = true
        if (!kotlinType.isMarkedNullable && field.get(instance) == null) {
            throw node.mappingError("missing value for non-null Kotlin property ${field.name}")
        }
    }

    private fun invoke(
        arguments: Map<KParameter, Any?>,
        failure: (String, Throwable) -> ConfigException,
    ): Any = try {
        constructor.callBy(arguments)
    } catch (error: Exception) {
        val cause = if (error is InvocationTargetException) error.targetException else error
        throw failure("Kotlin constructor failed for ${type.name}: ${cause.message}", cause)
    }

    private class ParameterPlan(
        val parameter: KParameter,
        val key: String,
        val field: ConfigTypePlan.FieldPlan?,
    )

    private fun convert(mapper: YamlConfigMapper, node: ConfigNode, target: KType): Any? {
        if (node.raw() == null) {
            if (!target.isMarkedNullable) {
                throw node.mappingError("null cannot be assigned to non-null Kotlin type $target")
            }
            return null
        }
        val classifier = target.classifier as? KClass<*>
            ?: throw node.mappingError("unresolved Kotlin target type $target")
        val rawClass = classifier.java
        val value: Any? = when {
            Collection::class.java.isAssignableFrom(rawClass) -> {
                if (!node.isSequence) throw node.mappingError("expected a sequence for $target")
                val elementType = argument(node, target, 0)
                val result = YamlConfigMapper.newCollection(node, rawClass)
                repeat(node.sequenceSize()) { index -> result.add(convert(mapper, node.index(index), elementType)) }
                result
            }
            Map::class.java.isAssignableFrom(rawClass) -> {
                val keyType = argument(node, target, 0)
                if (keyType.classifier != String::class || keyType.isMarkedNullable) {
                    throw node.mappingError("only Map<String, ?> is supported")
                }
                if (!node.isMapping) throw node.mappingError("expected a mapping for $target")
                val valueType = argument(node, target, 1)
                val result = YamlConfigMapper.newMap(node, rawClass)
                for (child in node.mappingChildren()) result[child.name()] = convert(mapper, child, valueType)
                result
            }
            rawClass.isArray && target.arguments.isNotEmpty() -> {
                if (!node.isSequence) throw node.mappingError("expected a sequence for $target")
                val elementType = argument(node, target, 0)
                val array = java.lang.reflect.Array.newInstance(rawClass.componentType, node.sequenceSize())
                repeat(node.sequenceSize()) { index ->
                    java.lang.reflect.Array.set(array, index, convert(mapper, node.index(index), elementType))
                }
                array
            }
            else -> mapper.convert(node, target.javaType)
        }
        if (value == null && !target.isMarkedNullable) {
            throw node.mappingError("converter returned null for non-null Kotlin type $target")
        }
        return value
    }

    private fun argument(node: ConfigNode, type: KType, index: Int): KType =
        type.arguments.getOrNull(index)?.type
            ?: throw node.mappingError("Kotlin collection type $type requires concrete type arguments")
}
