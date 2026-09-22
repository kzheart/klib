package me.kzheart.klib.command;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import me.kzheart.klib.command.annotation.*;
import me.kzheart.klib.command.api.*;
import me.kzheart.klib.reflect.Declarations;
import me.kzheart.klib.scope.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Compiles explicit handler instances into the existing command tree. */
public final class AnnotatedCommands {
    private static final AtomicLong IDS = new AtomicLong();
    private AnnotatedCommands() { }

    public static Disposable register(Scope owner, Object... handlers) {
        return owner.requireCapability(CommandCapability.class).registerAnnotated(owner, handlers);
    }

    static Disposable register(CommandCapabilityImpl capability, Scope owner, Object... handlers) {
        Objects.requireNonNull(handlers, "handlers");
        if (handlers.length == 0) throw new IllegalArgumentException("No command handlers");
        // Build the entire batch before touching Bukkit; the child scope rolls back bridge failures.
        return owner.scope("commands-" + IDS.incrementAndGet(), child -> {
            Map<String, CommandSpecImpl> roots = new LinkedHashMap<String, CommandSpecImpl>();
            Map<CommandNode, String> signatures = new IdentityHashMap<CommandNode, String>();
            Map<String, String> names = new LinkedHashMap<String, String>();
            for (Object target : handlers) {
                Objects.requireNonNull(target, "handler");
                Command command = target.getClass().getAnnotation(Command.class);
                if (command == null) throw new IllegalArgumentException(target.getClass().getName() + ": missing @Command");
                String canonical = word(command.value());
                Set<String> labels = new LinkedHashSet<String>();
                labels.add(canonical);
                for (String alias : command.aliases()) {
                    if (!labels.add(word(alias))) throw new IllegalArgumentException("Duplicate alias " + alias);
                }
                Map<String, Method> checks = new HashMap<String, Method>();
                Map<String, Method> suggestions = new HashMap<String, Method>();
                List<Method> methods = Declarations.methods(target.getClass());
                for (Method m : methods) {
                    CheckHandler check = m.getAnnotation(CheckHandler.class);
                    if (check != null) {
                        if (m.getParameterCount() == 0) Declarations.voidMethod(m, 0);
                        else {
                            Declarations.voidMethod(m, 1);
                            if (m.getParameterTypes()[0] != CommandCall.class) throw Declarations.invalid(m, "check accepts CommandCall or no arguments");
                        }
                        if (checks.put(check.value(), m) != null) throw Declarations.invalid(m, "duplicate check " + check.value());
                    }
                    Suggestions suggest = m.getAnnotation(Suggestions.class);
                    if (suggest != null) {
                        if (m.getParameterCount() != 1 || m.getParameterTypes()[0] != SuggestionContext.class
                                || m.getReturnType() != List.class
                                || !m.getGenericReturnType().getTypeName().equals("java.util.List<java.lang.String>")) {
                            throw Declarations.invalid(m, "suggestions must return List<String> and accept SuggestionContext");
                        }
                        if (suggestions.put(suggest.value(), m) != null) throw Declarations.invalid(m, "duplicate suggestions " + suggest.value());
                    }
                }
                for (String label : labels) {
                    String previous = names.putIfAbsent(label, canonical);
                    if (previous != null && !previous.equals(canonical)) throw new IllegalArgumentException("Conflicting command alias " + label);
                }
                boolean found = false;
                for (String label : Collections.singleton(canonical)) {
                    CommandSpecImpl root = roots.computeIfAbsent(label, CommandSpecImpl::command);
                    Description description = target.getClass().getAnnotation(Description.class);
                    if (description != null) root.description(description.value());
                    for (Method m : methods) {
                        Route route = m.getAnnotation(Route.class);
                        if (route == null) continue;
                        found = true;
                        if (route.value().length == 0) throw Declarations.invalid(m, "empty route declaration");
                        for (String path : route.value()) add(root.root(), signatures, target, m, path, checks, suggestions, child);
                    }
                }
                if (!found) throw new IllegalArgumentException(target.getClass().getName() + ": no @Route methods");
            }
            for (CommandSpecImpl spec : roots.values()) setVisibility(spec.root());
            for (Map.Entry<String, String> entry : names.entrySet()) {
                capability.registerCompiled(child, roots.get(entry.getValue()).withName(entry.getKey()));
            }
        });
    }

    private static void add(CommandNode root, Map<CommandNode, String> signatures, Object target,
            Method method, String path, Map<String, Method> checks, Map<String, Method> suggestions, Scope owner) {
        if (method.getReturnType() != Void.TYPE) throw Declarations.invalid(method, "route must return void; use CommandCall.await for async work");
        Map<String, Parameter> parameters = new LinkedHashMap<String, Parameter>();
        boolean playerOnly = false;
        for (Parameter parameter : method.getParameters()) {
            Param named = parameter.getAnnotation(Param.class);
            if (named != null) {
                if (parameters.put(word(named.value()), parameter) != null) throw Declarations.invalid(method, "duplicate @Param");
            } else {
                Class<?> type = parameter.getType();
                if (type == Player.class) playerOnly = true;
                else if (type != CommandSender.class && type != CommandCall.class && type != CommandContext.class) {
                    throw Declarations.invalid(method, "missing @Param on " + parameter);
                }
                if (parameter.isAnnotationPresent(Greedy.class) || parameter.isAnnotationPresent(Suggest.class)) {
                    throw Declarations.invalid(method, "@Greedy/@Suggest require @Param");
                }
            }
        }
        Permission permission = method.getAnnotation(Permission.class);
        if (permission == null) permission = target.getClass().getAnnotation(Permission.class);
        final String required = permission == null ? null : permission.value().trim();
        if (required != null && required.isEmpty()) throw Declarations.invalid(method, "blank permission");
        final boolean players = playerOnly;
        Predicate<CommandSender> access = sender -> (required == null || sender.hasPermission(required))
                && (!players || sender instanceof Player);
        List<Method> guards = new ArrayList<Method>();
        for (Check check : new Check[]{target.getClass().getAnnotation(Check.class), method.getAnnotation(Check.class)}) {
            if (check == null) continue;
            for (String name : check.value()) {
                Method guard = checks.get(name);
                if (guard == null) throw Declarations.invalid(method, "unknown check " + name);
                if (!guards.contains(guard)) guards.add(guard);
            }
        }
        String[] words = path.trim().isEmpty() ? new String[0] : path.trim().split("\\s+");
        Set<String> used = new HashSet<String>();
        CommandNode current = root;
        for (int i = 0; i < words.length; i++) {
            String token = words[i];
            boolean argument = token.startsWith("<") && token.endsWith(">");
            String name = word(argument ? token.substring(1, token.length() - 1) : token);
            if (!argument && (token.contains("<") || token.contains(">") || token.contains("[") || token.contains("]"))) {
                throw Declarations.invalid(method, "invalid path token " + token);
            }
            Parameter parameter = argument ? parameters.get(name) : null;
            if (argument && (parameter == null || !used.add(name))) throw Declarations.invalid(method, "unbound or duplicate path parameter " + name);
            if (current.argument != null && current.argument.isGreedy()) throw Declarations.invalid(method, "greedy parameter must be last");
            CommandNode next = null;
            for (CommandNode candidate : current.children) {
                if (argument && candidate.argument != null) {
                    if (!candidate.argument.name().equals(name)) throw Declarations.invalid(method, "ambiguous sibling parameters");
                    next = candidate;
                } else if (!argument && name.equals(candidate.literal)) next = candidate;
            }
            String signature = parameter == null ? "" : parameter.getType().getName() + ":"
                    + parameter.isAnnotationPresent(Greedy.class) + ":"
                    + (parameter.isAnnotationPresent(Suggest.class) ? parameter.getAnnotation(Suggest.class).value() + "@" + target.getClass().getName() : "");
            if (next == null) {
                next = new CommandNode(argument ? null : name,
                        argument ? argument(name, parameter, target, suggestions, method) : null);
                current.children.add(next);
                signatures.put(next, signature);
                if (parameter != null && parameter.isAnnotationPresent(Suggest.class)) next.suggestionOwner = target;
            } else if (!signature.equals(signatures.get(next))) throw Declarations.invalid(method, "conflicting parameter declaration " + name);
            if (next.suggestionOwner != null && next.suggestionOwner != target) {
                throw Declarations.invalid(method, "shared parameter uses suggestions from different instances: " + name);
            }
            current = next;
        }
        if (!used.equals(parameters.keySet())) throw Declarations.invalid(method, "@Param does not match route " + path);
        if (current.handler != null) throw Declarations.invalid(method, "duplicate route " + path);
        current.handlerAccess = access;
        current.handlerPlayerOnly = players;
        Description description = method.getAnnotation(Description.class);
        if (description != null) current.description = description.value();
        current.handler = context -> {
            if (owner.isClosed()) throw new CommandRejectedException("§c该功能已关闭");
            if (players && !(context.sender() instanceof Player)) throw new CommandRejectedException("§c该命令只能由玩家执行");
            if (!access.test(context.sender())) throw new CommandRejectedException("§c没有权限");
            CommandCall call = new CommandCall(context, owner);
            for (Method guard : guards) {
                Declarations.invoke(target, guard, guard.getParameterCount() == 0 ? new Object[0] : new Object[]{call});
            }
            Object[] values = new Object[method.getParameterCount()];
            Parameter[] declared = method.getParameters();
            for (int i = 0; i < values.length; i++) {
                Param param = declared[i].getAnnotation(Param.class);
                Class<?> type = declared[i].getType();
                values[i] = param != null ? context.get(word(param.value()), boxed(type))
                        : type == CommandCall.class ? call : type == CommandContext.class ? context : context.sender();
            }
            Declarations.invoke(target, method, values);
        };
    }

    private static Arg<?> argument(String name, Parameter parameter, Object target,
            Map<String, Method> suggestions, Method route) {
        Class<?> type = boxed(parameter.getType());
        boolean greedy = parameter.isAnnotationPresent(Greedy.class);
        if (greedy && type != String.class) throw Declarations.invalid(route, "@Greedy requires String");
        Arg<?> base;
        if (type == String.class) base = greedy ? Arguments.greedyString(name) : Arguments.string(name);
        else if (type == Integer.class) base = Arguments.integer(name);
        else if (type == Boolean.class) base = Arguments.bool(name);
        else if (type == Player.class) base = Arguments.player(name);
        else if (type == UUID.class) base = Arguments.custom(name, UUID::fromString, null);
        else if (type == Long.class) base = Arguments.custom(name, Long::valueOf, null);
        else if (type == Double.class) base = Arguments.custom(name, input -> {
            double value = Double.parseDouble(input);
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Expected finite number");
            return value;
        }, null);
        else if (type.isEnum()) base = Arguments.custom(name, input -> {
            for (Object value : type.getEnumConstants()) if (((Enum<?>) value).name().equalsIgnoreCase(input)) return value;
            throw new IllegalArgumentException("Unknown enum value");
        }, (sender, prefix) -> {
            List<String> names = new ArrayList<String>();
            for (Object value : type.getEnumConstants()) names.add(((Enum<?>) value).name().toLowerCase(Locale.ROOT));
            return names;
        });
        else throw Declarations.invalid(route, "unsupported argument type " + type.getName());
        Suggest declaration = parameter.getAnnotation(Suggest.class);
        Method provider = declaration == null ? null : suggestions.get(declaration.value());
        if (declaration != null && provider == null) throw Declarations.invalid(route, "unknown suggestions " + declaration.value());
        return new Arg<Object>(name, greedy) {
            @Override boolean isCatchAll() { return base.isCatchAll(); }
            @Override Object parse(String input, PlayerResolver players) throws ArgumentException { return base.parse(input, players); }
            @Override List<String> suggest(SuggestionContext context, PlayerResolver players) {
                if (provider == null) return base.suggest(context, players);
                Object result = Declarations.invoke(target, provider, context);
                List<String> filtered = new ArrayList<String>();
                if (result != null) for (Object value : (List<?>) result) {
                    if (!(value instanceof String)) throw Declarations.invalid(provider, "suggestion must be String");
                    String text = (String) value;
                    if (text.toLowerCase(Locale.ROOT).startsWith(context.prefix().toLowerCase(Locale.ROOT))) filtered.add(text);
                }
                return filtered;
            }
        };
    }

    private static void setVisibility(CommandNode node) {
        for (CommandNode child : node.children) setVisibility(child);
        node.playerOnly = node.handler == null || node.handlerPlayerOnly;
        for (CommandNode child : node.children) node.playerOnly &= child.playerOnly;
        node.branchAccess = sender -> {
            if (node.handlerAccess != null && node.handlerAccess.test(sender)) return true;
            for (CommandNode child : node.children) if (child.branchAccess.test(sender)) return true;
            return false;
        };
    }
    private static String word(String value) {
        String word = CommandSpecImpl.requireSingleWord(value, "route word");
        if (!word.matches("[a-z0-9_.:-]+")) throw new IllegalArgumentException("Invalid route word " + value);
        return word;
    }
    private static Class<?> boxed(Class<?> type) {
        if (type == Integer.TYPE) return Integer.class;
        if (type == Boolean.TYPE) return Boolean.class;
        if (type == Long.TYPE) return Long.class;
        if (type == Double.TYPE) return Double.class;
        return type;
    }
}
