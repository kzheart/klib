package me.kzheart.klib.command

import java.lang.reflect.Method
import java.lang.reflect.Parameter
import java.util.IdentityHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Predicate
import me.kzheart.klib.command.annotation.Check
import me.kzheart.klib.command.annotation.CheckHandler
import me.kzheart.klib.command.annotation.Command
import me.kzheart.klib.command.annotation.Description
import me.kzheart.klib.command.annotation.Greedy
import me.kzheart.klib.command.annotation.Param
import me.kzheart.klib.command.annotation.Permission
import me.kzheart.klib.command.annotation.Route
import me.kzheart.klib.command.annotation.Suggest
import me.kzheart.klib.command.annotation.Suggestions
import me.kzheart.klib.command.api.CommandCapability
import me.kzheart.klib.command.api.CommandContext
import me.kzheart.klib.command.api.CommandHandler
import me.kzheart.klib.reflect.Declarations
import me.kzheart.klib.scope.Disposable
import me.kzheart.klib.scope.Scope
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** Compiles a whole annotation batch before registration, so bridge failures roll back together. */
class AnnotatedCommands private constructor() {
    /** Reflection contracts are checked once per handler, independently from route assembly. */
    private class HandlerDeclarations(val target: Any) {
        val methods: List<Method> = Declarations.methods(target.javaClass)
        val checks = linkedMapOf<String, Method>()
        val suggestions = linkedMapOf<String, Method>()
        init {
            for (method in methods) {
                method.getAnnotation(CheckHandler::class.java)?.let { check ->
                    if (method.parameterCount == 0) Declarations.voidMethod(method, 0)
                    else {
                        Declarations.voidMethod(method, 1)
                        if (method.parameterTypes[0] != CommandCall::class.java) throw Declarations.invalid(method, "check accepts CommandCall or no arguments")
                    }
                    if (checks.put(check.value, method) != null) throw Declarations.invalid(method, "duplicate check ${check.value}")
                }
                method.getAnnotation(Suggestions::class.java)?.let { suggest ->
                    if (method.parameterCount != 1 || method.parameterTypes[0] != SuggestionContext::class.java ||
                        method.returnType != List::class.java || method.genericReturnType.typeName != "java.util.List<java.lang.String>") {
                        throw Declarations.invalid(method, "suggestions must return List<String> and accept SuggestionContext")
                    }
                    if (suggestions.put(suggest.value, method) != null) throw Declarations.invalid(method, "duplicate suggestions ${suggest.value}")
                }
            }
        }
        fun guards(method: Method): List<Method> = listOfNotNull(target.javaClass.getAnnotation(Check::class.java), method.getAnnotation(Check::class.java))
            .flatMap { it.value.toList() }.map { checks[it] ?: throw Declarations.invalid(method, "unknown check $it") }.distinct()
    }

    companion object {
        private val ids = AtomicLong()
        @JvmStatic fun register(owner: Scope, vararg handlers: Any): Disposable =
            owner.requireCapability(CommandCapability::class.java).registerAnnotated(owner, *handlers)
        @JvmStatic fun register(capability: CommandCapabilityImpl, owner: Scope, vararg handlers: Any): Disposable {
            require(handlers.isNotEmpty()) { "No command handlers" }
            return owner.scope("commands-${ids.incrementAndGet()}") { child ->
                val roots = linkedMapOf<String, CommandSpecImpl>()
                val signatures = IdentityHashMap<CommandNode, String>()
                val names = linkedMapOf<String, String>()
                val helpRoots = linkedSetOf<String>()
                for (entry in handlers) {
                    val mount = entry as? MountedCommand
                    val target = mount?.handler() ?: entry
                    val command = target.javaClass.getAnnotation(Command::class.java)
                    if (mount == null && command == null) throw IllegalArgumentException("${target.javaClass.name}: missing @Command")
                    val canonical = word(mount?.command() ?: requireNotNull(command).value)
                    if (mount == null && requireNotNull(command).help) helpRoots.add(canonical)
                    val labels = linkedSetOf(canonical)
                    val prefixes = if (mount == null) {
                        requireNotNull(command).aliases.forEach { alias -> require(labels.add(word(alias))) { "Duplicate alias $alias" } }
                        listOf("")
                    } else {
                        val unique = linkedSetOf<String>()
                        mount.literals().forEach { literal ->
                            val prefix = literal.trim().split(Regex("\\s+")).joinToString(" ") { word(it) }
                            require(unique.add(prefix)) { "Duplicate mount literal $literal" }
                        }
                        unique.toList()
                    }
                    val declaration = HandlerDeclarations(target)
                    for (label in labels) {
                        val previous = names.putIfAbsent(label, canonical)
                        require(previous == null || previous == canonical) { "Conflicting command alias $label" }
                    }
                    val root = roots.getOrPut(canonical) { CommandSpecImpl.command(canonical) }
                    if (mount == null) target.javaClass.getAnnotation(Description::class.java)?.let { root.description(it.value) }
                    var found = false
                    for (method in declaration.methods) {
                        val route = method.getAnnotation(Route::class.java) ?: continue
                        found = true
                        if (route.value.isEmpty()) throw Declarations.invalid(method, "empty route declaration")
                        for (prefix in prefixes) for (path in route.value) {
                            add(root.root(), signatures, declaration, method, "$prefix $path".trim(), child)
                        }
                    }
                    require(found) { "${target.javaClass.name}: no @Route methods" }
                }
                for (name in helpRoots) {
                    val spec = requireNotNull(roots[name])
                    if (spec.root().children.none { it.literal == "help" }) CommandBuiltins.create().install(spec)
                }
                roots.values.forEach { setVisibility(it.root()) }
                names.forEach { (label, canonical) -> capability.registerCompiled(child, requireNotNull(roots[canonical]).withName(label)) }
            }
        }

        private fun add(root: CommandNode, signatures: MutableMap<CommandNode, String>, declaration: HandlerDeclarations,
            method: Method, path: String, owner: Scope) {
            if (method.returnType != Void.TYPE) throw Declarations.invalid(method, "route must return void; use CommandCall.await for async work")
            val parameters = linkedMapOf<String, Parameter>()
            var playerOnly = false
            for (parameter in method.parameters) {
                val named = parameter.getAnnotation(Param::class.java)
                if (named != null) {
                    if (parameters.put(word(named.value), parameter) != null) throw Declarations.invalid(method, "duplicate @Param")
                } else {
                    when (parameter.type) {
                        Player::class.java -> playerOnly = true
                        CommandSender::class.java, CommandCall::class.java, CommandContext::class.java -> Unit
                        else -> throw Declarations.invalid(method, "missing @Param on $parameter")
                    }
                    if (parameter.isAnnotationPresent(Greedy::class.java) || parameter.isAnnotationPresent(Suggest::class.java)) {
                        throw Declarations.invalid(method, "@Greedy/@Suggest require @Param")
                    }
                }
            }
            val required = (method.getAnnotation(Permission::class.java) ?: declaration.target.javaClass.getAnnotation(Permission::class.java))?.value?.trim()
            if (required != null && required.isEmpty()) throw Declarations.invalid(method, "blank permission")
            val players = playerOnly
            val access = Predicate<CommandSender> { (required == null || it.hasPermission(required)) && (!players || it is Player) }
            val guards = declaration.guards(method)
            val words = if (path.trim().isEmpty()) emptyList() else path.trim().split(Regex("\\s+"))
            val used = hashSetOf<String>()
            var current = root
            for (token in words) {
                val argument = token.startsWith('<') && token.endsWith('>')
                val name = word(if (argument) token.substring(1, token.length - 1) else token)
                if (!argument && token.any { it in "<>[]" }) throw Declarations.invalid(method, "invalid path token $token")
                val parameter = if (argument) parameters[name] else null
                if (argument && (parameter == null || !used.add(name))) throw Declarations.invalid(method, "unbound or duplicate path parameter $name")
                if (current.argument?.isGreedy == true) throw Declarations.invalid(method, "greedy parameter must be last")
                var next: CommandNode? = null
                for (candidate in current.children) {
                    if (argument && candidate.argument != null) {
                        if (candidate.argument.name() != name) throw Declarations.invalid(method, "ambiguous sibling parameters")
                        next = candidate
                    } else if (!argument && candidate.literal == name) next = candidate
                }
                val suggest = parameter?.getAnnotation(Suggest::class.java)
                val signature = parameter?.let { "${it.type.name}:${it.isAnnotationPresent(Greedy::class.java)}:" +
                    (suggest?.let { s -> "${s.value}@${declaration.target.javaClass.name}" } ?: "") } ?: ""
                val selected = next ?: CommandNode(if (argument) null else name,
                    if (argument) argument(name, requireNotNull(parameter), declaration, method) else null).also {
                    it.parent = current
                    current.children.add(it)
                    signatures[it] = signature
                    if (suggest != null) it.suggestionOwner = declaration.target
                }
                if (next != null && signature != signatures[selected]) throw Declarations.invalid(method, "conflicting parameter declaration $name")
                if (selected.suggestionOwner != null && selected.suggestionOwner !== declaration.target) {
                    throw Declarations.invalid(method, "shared parameter uses suggestions from different instances: $name")
                }
                current = selected
            }
            if (used != parameters.keys) throw Declarations.invalid(method, "@Param does not match route $path")
            if (current.handler != null) throw Declarations.invalid(method, "duplicate route $path")
            current.handlerAccess = access
            current.handlerPlayerOnly = players
            method.getAnnotation(Description::class.java)?.let { current.description = it.value }
            current.handler = CommandHandler { context ->
                if (owner.isClosed) throw CommandRejectedException("§c该功能已关闭")
                if (players && context.sender() !is Player) throw CommandRejectedException("§c该命令只能由玩家执行")
                if (!access.test(context.sender())) throw CommandRejectedException("§c没有权限")
                val call = CommandCall(context, owner)
                guards.forEach { guard -> Declarations.invoke(declaration.target, guard, *if (guard.parameterCount == 0) emptyArray() else arrayOf(call)) }
                val values = method.parameters.map { parameter ->
                    val param = parameter.getAnnotation(Param::class.java)
                    if (param != null) context.get(word(param.value), boxed(parameter.type))
                    else when (parameter.type) {
                        CommandCall::class.java -> call
                        CommandContext::class.java -> context
                        else -> context.sender()
                    }
                }.toTypedArray()
                Declarations.invoke(declaration.target, method, *values)
            }
        }

        private fun argument(name: String, parameter: Parameter, declaration: HandlerDeclarations, route: Method): Arg<*> {
            val type = boxed(parameter.type)
            val greedy = parameter.isAnnotationPresent(Greedy::class.java)
            if (greedy && type != String::class.java) throw Declarations.invalid(route, "@Greedy requires String")
            val base: Arg<*> = when {
                type == String::class.java -> if (greedy) Arguments.greedyString(name) else Arguments.string(name)
                type == Int::class.javaObjectType -> Arguments.integer(name)
                type == Boolean::class.javaObjectType -> Arguments.bool(name)
                type == Player::class.java -> Arguments.player(name)
                type == UUID::class.java -> Arguments.custom(name, { UUID.fromString(it) }, null)
                type == Long::class.javaObjectType -> Arguments.custom(name, { it.toLong() }, null)
                type == Double::class.javaObjectType -> Arguments.custom(name, { input ->
                    input.toDouble().also { require(it.isFinite()) { "Expected finite number" } }
                }, null)
                type.isEnum -> Arguments.custom(name, { input ->
                    type.enumConstants.firstOrNull { (it as Enum<*>).name.equals(input, ignoreCase = true) }
                        ?: throw IllegalArgumentException("Unknown enum value")
                }, { _, _ -> type.enumConstants.map { (it as Enum<*>).name.lowercase(Locale.ROOT) } })
                else -> throw Declarations.invalid(route, "unsupported argument type ${type.name}")
            }
            val suggest = parameter.getAnnotation(Suggest::class.java)
            val provider = suggest?.let { declaration.suggestions[it.value]
                ?: throw Declarations.invalid(route, "unknown suggestions ${it.value}") }
            return object : Arg<Any?>(name, greedy) {
                override fun isCatchAll(): Boolean = base.isCatchAll
                @Throws(ArgumentException::class)
                override fun parse(input: String, players: PlayerResolver): Any? = base.parse(input, players)
                override fun suggest(context: SuggestionContext, players: PlayerResolver): List<String> {
                    if (provider == null) return base.suggest(context, players)
                    val result = Declarations.invoke(declaration.target, provider, context) as? List<*> ?: return emptyList()
                    return result.map { value -> value as? String ?: throw Declarations.invalid(provider, "suggestion must be String") }
                        .filter { it.lowercase(Locale.ROOT).startsWith(context.prefix().lowercase(Locale.ROOT)) }
                }
            }
        }
        private fun setVisibility(node: CommandNode) {
            node.children.forEach { setVisibility(it) }
            node.playerOnly = (node.handler == null || node.handlerPlayerOnly) && node.children.all { it.playerOnly }
            node.branchAccess = Predicate { sender ->
                (node.handler != null && node.handlerAccess?.test(sender) != false) || node.children.any { it.branchAccess!!.test(sender) }
            }
        }
        private fun word(value: String): String = CommandSpecImpl.requireSingleWord(value, "route word").also {
            require(Regex("[\\p{L}\\p{N}_.:-]+").matches(it)) { "Invalid route word $value" }
        }
        private fun boxed(type: Class<*>): Class<*> = when (type) {
            Int::class.javaPrimitiveType -> Int::class.javaObjectType
            Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
            Long::class.javaPrimitiveType -> Long::class.javaObjectType
            Double::class.javaPrimitiveType -> Double::class.javaObjectType
            else -> type
        }
    }
}
