package me.kzheart.klib.config

import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.time.Duration
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import me.kzheart.klib.config.annotation.Validate
import me.kzheart.klib.random.DoubleRange
import me.kzheart.klib.random.IntRange
import me.kzheart.klib.reflect.Declarations

/** Maps path-aware YAML nodes to Java fields or Kotlin primary constructors. */
class YamlConfigMapper @JvmOverloads constructor(
    private val unknownKeyPolicy: UnknownKeyPolicy = UnknownKeyPolicy.WARN,
) {
    private val converters = linkedMapOf<Class<*>, ConfigConverter<*>>()
    private val warnedUnknownKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun <T> registerConverter(type: Class<T>, converter: ConfigConverter<T?>): YamlConfigMapper = apply {
        synchronized(converters) { converters[type] = converter }
    }

    /** Kotlin also accepts a predeclared non-null Java SAM without changing its JVM generic signature. */
    @JvmSynthetic
    fun <T> registerConverter(type: Class<T>, converter: ConfigConverter<T>,
        @Suppress("UNUSED_PARAMETER") typed: Unit = Unit): YamlConfigMapper =
        registerConverter(type, ConfigConverter<T?> { node -> converter.convert(node) })

    fun <T> read(node: ConfigNode, type: Class<T>): T = type.cast(convert(node, type))

    @JvmName("convert") internal fun convert(node: ConfigNode, targetType: Type): Any? = when (targetType) {
        is Class<*> -> convertClass(node, targetType)
        is ParameterizedType -> convertParameterized(node, targetType)
        is WildcardType -> convert(node, targetType.upperBounds.firstOrNull() ?: Any::class.java)
        is GenericArrayType -> throw node.mappingError("generic arrays are not supported")
        else -> throw node.mappingError("unsupported target type $targetType")
    }

    private fun convertClass(node: ConfigNode, type: Class<*>): Any? {
        val converter = synchronized(converters) { converters[type] }
        if (converter != null) {
            return try {
                converter.convert(node)
            } catch (failure: ConfigException) {
                throw failure
            } catch (failure: RuntimeException) {
                throw node.mappingError("custom converter failed for ${type.name}", failure)
            }
        }
        val raw = node.raw() ?: return if (type.isPrimitive) {
            throw node.mappingError("null cannot be assigned to primitive ${type.name}")
        } else null
        return when (type) {
            Any::class.java -> raw
            String::class.java -> raw as? String
                ?: throw node.mappingError("expected a string, got ${kind(raw)}")
            Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> raw as? Boolean
                ?: throw node.mappingError("expected a boolean, got ${kind(raw)}")
            Char::class.javaPrimitiveType, Char::class.javaObjectType ->
                (raw as? String)?.singleOrNull() ?: throw node.mappingError("expected a single character")
            Duration::class.java -> parseDuration(node, raw as? String
                ?: throw node.mappingError("expected a duration string, got ${kind(raw)}"))
            IntRange::class.java -> convertIntRange(node, raw)
            DoubleRange::class.java -> convertDoubleRange(node, raw)
            else -> when {
                type in numericTypes -> convertNumber(node, raw, type)
                type.isEnum -> convertEnum(node, raw, type)
                type.isArray -> convertArray(node, type.componentType)
                Collection::class.java.isAssignableFrom(type) || Map::class.java.isAssignableFrom(type) ->
                    throw node.mappingError("collection type ${type.name} must declare generic arguments")
                else -> convertPojo(node, type)
            }
        }
    }

    private fun convertParameterized(node: ConfigNode, target: ParameterizedType): Any {
        val rawClass = target.rawType as? Class<*>
            ?: throw node.mappingError("unsupported parameterized type $target")
        val arguments = target.actualTypeArguments
        return when {
            Collection::class.java.isAssignableFrom(rawClass) -> {
                requireSequence(node)
                newCollection(node, rawClass).apply {
                    repeat(node.sequenceSize()) { add(convert(node.index(it), arguments[0])) }
                }
            }
            Map::class.java.isAssignableFrom(rawClass) -> {
                if (arguments[0] != String::class.java) throw node.mappingError("only Map<String, ?> is supported")
                if (!node.isMapping) throw node.mappingError("expected a mapping, got ${kind(node.raw())}")
                newMap(node, rawClass).apply {
                    node.mappingChildren().forEach { put(it.name(), convert(it, arguments[1])) }
                }
            }
            else -> convertPojo(node, rawClass)
        }
    }

    private fun convertPojo(node: ConfigNode, type: Class<*>): Any {
        if (!node.isMapping) throw node.mappingError("expected a mapping for ${type.name}")
        if (type.isInterface || Modifier.isAbstract(type.modifiers)) throw node.mappingError("cannot instantiate ${type.name}")
        val plan = try {
            ConfigTypePlan.of(type)
        } catch (failure: ConfigException) {
            throw node.mappingError(failure.message, failure)
        }
        if (unknownKeyPolicy == UnknownKeyPolicy.REJECT) checkUnknownKeys(node, type, plan.declaredNames)
        val instance = plan.kotlin?.construct(this, node) ?: try {
            type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        } catch (failure: ReflectiveOperationException) {
            throw node.mappingError("${type.name} requires an accessible no-argument constructor", failure)
        }
        plan.fields.filterNot { it.ignored }.forEach { bindField(node, instance, plan, it) }
        Declarations.methods(type).filter { it.isAnnotationPresent(Validate::class.java) }.forEach { method ->
            try {
                Declarations.voidMethod(method, 0)
                Declarations.invoke(instance, method)
            } catch (failure: RuntimeException) {
                throw node.mappingError("validation failed at ${method.name}: ${failure.message}", failure)
            }
        }
        if (unknownKeyPolicy == UnknownKeyPolicy.WARN) checkUnknownKeys(node, type, plan.declaredNames)
        return instance
    }

    private fun bindField(node: ConfigNode, instance: Any, plan: ConfigTypePlan, declaration: ConfigTypePlan.FieldPlan) {
        val field = declaration.field
        val child = node.child(declaration.name)
        if (!child.exists() || plan.kotlin?.isConstructorField(field) == true) {
            plan.kotlin?.validateField(instance, child, field)
            validateRange(instance, declaration, child)
            return
        }
        if (Modifier.isFinal(field.modifiers)) throw child.mappingError("cannot assign final field ${field.name}")
        val value = if (plan.kotlin == null) convert(child, field.genericType)
            else plan.kotlin.convertField(this, child, field)
        try {
            field.isAccessible = true
            field.set(instance, value)
            validateRange(instance, declaration, child)
        } catch (failure: IllegalAccessException) {
            throw child.mappingError("cannot assign field ${field.name}", failure)
        } catch (failure: IllegalArgumentException) {
            throw child.mappingError("invalid value for field ${field.name}", failure)
        }
    }

    /** User-defined map keys are deliberately checked only at model boundaries. */
    private fun checkUnknownKeys(node: ConfigNode, type: Class<*>, declaredNames: Set<String>) {
        for (child in node.mappingChildren()) {
            val key = child.name()
            if (key in declaredNames || (node.path().isEmpty() && key == "_schema-version")) continue
            if (unknownKeyPolicy == UnknownKeyPolicy.REJECT) {
                throw child.keyMappingError("unknown configuration key; ${type.name} has no matching field")
            }
            val keyPath = if (node.path().isEmpty()) key else "${node.path()}.$key"
            if (warnedUnknownKeys.add("${node.sourceName()}\u0000$keyPath")) {
                logger.warning("${child.keyLocationPrefix()}: unknown configuration key is ignored; ${type.name} has no matching field")
            }
        }
    }

    private fun convertArray(node: ConfigNode, componentType: Class<*>): Any {
        requireSequence(node)
        return ReflectArray.newInstance(componentType, node.sequenceSize()).also { array ->
            repeat(node.sequenceSize()) { ReflectArray.set(array, it, convert(node.index(it), componentType)) }
        }
    }

    companion object {
        private val logger = Logger.getLogger(YamlConfigMapper::class.java.name)
        private val durationSegment = Regex("([0-9]+)(ms|[smhd])")
        private val numericTypes = setOf(
            Byte::class.javaPrimitiveType, Byte::class.javaObjectType,
            Short::class.javaPrimitiveType, Short::class.javaObjectType,
            Int::class.javaPrimitiveType, Int::class.javaObjectType,
            Long::class.javaPrimitiveType, Long::class.javaObjectType,
            Float::class.javaPrimitiveType, Float::class.javaObjectType,
            Double::class.javaPrimitiveType, Double::class.javaObjectType,
        )

        @JvmStatic @JvmName("newCollection") internal fun newCollection(node: ConfigNode, type: Class<*>): MutableCollection<Any?> {
            if (type.isInterface || Modifier.isAbstract(type.modifiers)) {
                return if (Set::class.java.isAssignableFrom(type)) linkedSetOf() else arrayListOf()
            }
            @Suppress("UNCHECKED_CAST")
            return instantiateContainer(node, type) as? MutableCollection<Any?>
                ?: throw node.mappingError("not a collection: ${type.name}")
        }

        @JvmStatic @JvmName("newMap") internal fun newMap(node: ConfigNode, type: Class<*>): MutableMap<String, Any?> {
            if (type.isInterface || Modifier.isAbstract(type.modifiers)) return linkedMapOf()
            @Suppress("UNCHECKED_CAST")
            return instantiateContainer(node, type) as? MutableMap<String, Any?>
                ?: throw node.mappingError("not a map: ${type.name}")
        }

        private fun instantiateContainer(node: ConfigNode, type: Class<*>): Any = try {
            type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        } catch (failure: ReflectiveOperationException) {
            throw node.mappingError("cannot instantiate ${type.name}", failure)
        }

        private fun requireSequence(node: ConfigNode) {
            if (!node.isSequence) throw node.mappingError("expected a sequence, got ${kind(node.raw())}")
        }

        private fun validateRange(instance: Any, declaration: ConfigTypePlan.FieldPlan, node: ConfigNode) {
            val range = declaration.range ?: return
            val field = declaration.field
            try {
                field.isAccessible = true
                val value = field.get(instance)
                if (value !is Number || !range.min.isFinite() || !range.max.isFinite() || range.min > range.max) {
                    throw node.mappingError("@Range requires a number and finite ordered bounds")
                }
                val number = value.toDouble()
                if (!number.isFinite() || number < range.min || number > range.max) {
                    throw node.mappingError("value must be between ${range.min} and ${range.max}")
                }
            } catch (failure: IllegalAccessException) {
                throw node.mappingError("cannot validate ${field.name}", failure)
            }
        }

        private fun convertNumber(node: ConfigNode, raw: Any?, type: Class<*>): Number {
            val number = raw as? Number ?: throw node.mappingError("expected a number, got ${kind(raw)}")
            return when (type) {
                Byte::class.javaPrimitiveType, Byte::class.javaObjectType -> boundedIntegral(node, number, Byte.MIN_VALUE.toLong(), Byte.MAX_VALUE.toLong(), "byte").toByte()
                Short::class.javaPrimitiveType, Short::class.javaObjectType -> boundedIntegral(node, number, Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong(), "short").toShort()
                Int::class.javaPrimitiveType, Int::class.javaObjectType -> boundedIntegral(node, number, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong(), "integer").toInt()
                Long::class.javaPrimitiveType, Long::class.javaObjectType -> integral(node, number)
                Float::class.javaPrimitiveType, Float::class.javaObjectType -> number.toFloat()
                Double::class.javaPrimitiveType, Double::class.javaObjectType -> number.toDouble()
                else -> throw node.mappingError("unsupported numeric target ${type.name}")
            }
        }

        private fun boundedIntegral(node: ConfigNode, number: Number, min: Long, max: Long, kind: String): Long =
            integral(node, number).also { if (it < min || it > max) throw node.mappingError("number is outside $kind range") }

        private fun integral(node: ConfigNode, number: Number): Long {
            if (number is Float || number is Double) {
                val value = number.toDouble()
                if (!value.isFinite() || value != Math.rint(value)) throw node.mappingError("expected an integer, got $number")
            }
            return number.toLong()
        }

        private fun convertEnum(node: ConfigNode, raw: Any, type: Class<*>): Any {
            if (raw !is String) throw node.mappingError("expected an enum name, got ${kind(raw)}")
            return type.enumConstants.firstOrNull { (it as Enum<*>).name.equals(raw, ignoreCase = true) }
                ?: throw node.mappingError("unknown ${type.simpleName} value '$raw'")
        }

        private fun parseDuration(node: ConfigNode, value: String): Duration = try {
            if (value.startsWith("P", ignoreCase = true)) Duration.parse(value.uppercase(Locale.ROOT))
            else {
                val normalized = value.trim().lowercase(Locale.ROOT)
                var end = 0
                var result = Duration.ZERO
                for (segment in durationSegment.findAll(normalized)) {
                    require(segment.range.first == end) { "invalid duration segment" }
                    val amount = segment.groupValues[1].toLong()
                    result = when (segment.groupValues[2]) {
                        "ms" -> result.plusMillis(amount)
                        "s" -> result.plusSeconds(amount)
                        "m" -> result.plusMinutes(amount)
                        "h" -> result.plusHours(amount)
                        else -> result.plusDays(amount)
                    }
                    end = segment.range.last + 1
                }
                require(end != 0 && end == normalized.length) { "missing or invalid duration unit" }
                result
            }
        } catch (failure: RuntimeException) {
            throw node.mappingError("invalid duration '$value'", failure)
        }

        private fun convertIntRange(node: ConfigNode, raw: Any): IntRange = mapRangeFailure(node) {
            when (raw) {
                is String -> IntRange.parse(raw)
                is Number -> IntRange.exactly(convertNumber(node, raw, Int::class.javaObjectType).toInt())
                is Map<*, *> -> IntRange.of(
                    convertNumber(node.child("min"), node.child("min").raw(), Int::class.javaObjectType).toInt(),
                    convertNumber(node.child("max"), node.child("max").raw(), Int::class.javaObjectType).toInt())
                else -> throw node.mappingError("expected an integer range like 1-5, got ${kind(raw)}")
            }
        }

        private fun convertDoubleRange(node: ConfigNode, raw: Any): DoubleRange = mapRangeFailure(node) {
            when (raw) {
                is String -> DoubleRange.parse(raw)
                is Number -> DoubleRange.exactly(raw.toDouble())
                is Map<*, *> -> DoubleRange.of(
                    convertNumber(node.child("min"), node.child("min").raw(), Double::class.javaObjectType).toDouble(),
                    convertNumber(node.child("max"), node.child("max").raw(), Double::class.javaObjectType).toDouble())
                else -> throw node.mappingError("expected a number range like 0.5~2.5, got ${kind(raw)}")
            }
        }

        private inline fun <T> mapRangeFailure(node: ConfigNode, operation: () -> T): T = try {
            operation()
        } catch (failure: ConfigException) {
            throw failure
        } catch (failure: IllegalArgumentException) {
            throw node.mappingError(failure.message, failure)
        }

        private fun kind(value: Any?): String = value?.javaClass?.simpleName ?: "null"
    }
}
