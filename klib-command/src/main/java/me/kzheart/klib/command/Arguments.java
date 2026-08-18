package me.kzheart.klib.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class Arguments {
    private static final List<String> BOOLEAN_VALUES = Arrays.asList(
            "true", "false", "yes", "no", "on", "off", "1", "0");

    private Arguments() {
    }

    public static Arg<String> string(String name) {
        return string(name, null);
    }

    public static Arg<String> string(String name, final SuggestionProvider suggestions) {
        return new Arg<String>(name, false) {
            @Override
            String parse(String input, PlayerResolver players) {
                return input;
            }

            @Override
            boolean isCatchAll() {
                return true;
            }

            @Override
            List<String> suggest(CommandSender sender, String prefix, PlayerResolver players) {
                return suggestions == null
                        ? Collections.<String>emptyList()
                        : safeSuggestions(suggestions.suggest(sender, prefix), prefix);
            }
        };
    }

    /**
     * 自定义参数扩展点：parser 返回 {@code null} 或抛出
     * {@link IllegalArgumentException} 视为解析失败，suggester 可为 {@code null}。
     */
    public static <T> Arg<T> custom(
            String name,
            final ArgumentParser<T> parser,
            final SuggestionProvider suggester
    ) {
        if (parser == null) {
            throw new NullPointerException("parser");
        }
        return new Arg<T>(name, false) {
            @Override
            T parse(String input, PlayerResolver players) throws ArgumentException {
                final T value;
                try {
                    value = parser.parse(input);
                } catch (IllegalArgumentException failure) {
                    throw new ArgumentException(
                            CommandMessageKeys.UNKNOWN_ARGUMENT,
                            "argument", input);
                }
                if (value == null) {
                    throw new ArgumentException(
                            CommandMessageKeys.UNKNOWN_ARGUMENT,
                            "argument", input);
                }
                return value;
            }

            @Override
            List<String> suggest(CommandSender sender, String prefix, PlayerResolver players) {
                return suggester == null
                        ? Collections.<String>emptyList()
                        : safeSuggestions(suggester.suggest(sender, prefix), prefix);
            }
        };
    }

    public static Arg<Integer> integer(String name) {
        return integer(name, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    public static Arg<Integer> integer(String name, final int minimum, final int maximum) {
        if (minimum > maximum) {
            throw new IllegalArgumentException("minimum must not exceed maximum");
        }
        return new Arg<Integer>(name, false) {
            @Override
            Integer parse(String input, PlayerResolver players) throws ArgumentException {
                final int parsed;
                try {
                    parsed = Integer.parseInt(input);
                } catch (NumberFormatException exception) {
                    throw new ArgumentException(CommandMessageKeys.ARG_INTEGER);
                }
                if (parsed < minimum || parsed > maximum) {
                    throw new ArgumentException(
                            CommandMessageKeys.ARG_INTEGER_RANGE,
                            "minimum", Integer.valueOf(minimum),
                            "maximum", Integer.valueOf(maximum),
                            "range", range(
                                    minimum == Integer.MIN_VALUE
                                            ? null
                                            : String.valueOf(minimum),
                                    maximum == Integer.MAX_VALUE
                                            ? null
                                            : String.valueOf(maximum)));
                }
                return parsed;
            }
        };
    }

    public static Arg<BigDecimal> decimal(String name) {
        return decimal(name, null, null);
    }

    public static Arg<BigDecimal> decimal(
            String name,
            final BigDecimal minimum,
            final BigDecimal maximum
    ) {
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("minimum must not exceed maximum");
        }
        return new Arg<BigDecimal>(name, false) {
            @Override
            BigDecimal parse(String input, PlayerResolver players) throws ArgumentException {
                final BigDecimal parsed;
                try {
                    parsed = new BigDecimal(input);
                } catch (NumberFormatException exception) {
                    throw new ArgumentException(CommandMessageKeys.ARG_DECIMAL);
                }
                if (minimum != null && parsed.compareTo(minimum) < 0
                        || maximum != null && parsed.compareTo(maximum) > 0) {
                    throw new ArgumentException(
                            CommandMessageKeys.ARG_DECIMAL_RANGE,
                            "minimum", minimum == null ? "-∞" : minimum.toPlainString(),
                            "maximum", maximum == null ? "∞" : maximum.toPlainString(),
                            "range", range(
                                    minimum == null ? null : minimum.toPlainString(),
                                    maximum == null ? null : maximum.toPlainString()));
                }
                return parsed;
            }
        };
    }

    public static Arg<Boolean> bool(String name) {
        return new Arg<Boolean>(name, false) {
            @Override
            Boolean parse(String input, PlayerResolver players) throws ArgumentException {
                String normalized = input.toLowerCase(Locale.ROOT);
                if ("true".equals(normalized) || "yes".equals(normalized)
                        || "on".equals(normalized) || "1".equals(normalized)) {
                    return Boolean.TRUE;
                }
                if ("false".equals(normalized) || "no".equals(normalized)
                        || "off".equals(normalized) || "0".equals(normalized)) {
                    return Boolean.FALSE;
                }
                throw new ArgumentException(CommandMessageKeys.ARG_BOOLEAN);
            }

            @Override
            List<String> suggest(CommandSender sender, String prefix, PlayerResolver players) {
                return matching(BOOLEAN_VALUES, prefix);
            }
        };
    }

    public static <E extends Enum<E>> Arg<E> enumeration(String name, final Class<E> type) {
        if (type == null) {
            throw new NullPointerException("type");
        }
        return new Arg<E>(name, false) {
            @Override
            E parse(String input, PlayerResolver players) throws ArgumentException {
                for (E value : type.getEnumConstants()) {
                    if (value.name().equalsIgnoreCase(input)) {
                        return value;
                    }
                }
                throw new ArgumentException(
                        CommandMessageKeys.ARG_CHOICE,
                        "choices", String.join(", ", names(type)));
            }

            @Override
            List<String> suggest(CommandSender sender, String prefix, PlayerResolver players) {
                return matching(names(type), prefix);
            }
        };
    }

    public static Arg<Player> player(String name) {
        return new Arg<Player>(name, false) {
            @Override
            Player parse(String input, PlayerResolver players) throws ArgumentException {
                Player player = players.findExact(input);
                if (player == null) {
                    throw new ArgumentException(
                            CommandMessageKeys.ARG_PLAYER_OFFLINE,
                            "player", input);
                }
                return player;
            }

            @Override
            List<String> suggest(CommandSender sender, String prefix, PlayerResolver players) {
                return safeSuggestions(players.suggest(prefix), prefix);
            }
        };
    }

    public static Arg<String> greedyString(String name) {
        return new Arg<String>(name, true) {
            @Override
            String parse(String input, PlayerResolver players) throws ArgumentException {
                if (input.isEmpty()) {
                    throw new ArgumentException(CommandMessageKeys.ARG_CONTENT_EMPTY);
                }
                return input;
            }
        };
    }

    public static Arg<String> choice(String name, String... choices) {
        if (choices == null || choices.length == 0) {
            throw new IllegalArgumentException("choices must not be empty");
        }
        final List<String> values = new ArrayList<String>();
        for (String choice : choices) {
            if (choice == null || choice.trim().isEmpty()) {
                throw new IllegalArgumentException("choice must not be blank");
            }
            values.add(choice.trim());
        }
        return new Arg<String>(name, false) {
            @Override
            String parse(String input, PlayerResolver players) throws ArgumentException {
                for (String value : values) {
                    if (value.equalsIgnoreCase(input)) {
                        return value;
                    }
                }
                throw new ArgumentException(
                        CommandMessageKeys.ARG_CHOICE,
                        "choices", String.join(", ", values));
            }

            @Override
            List<String> suggest(CommandSender sender, String prefix, PlayerResolver players) {
                return matching(values, prefix);
            }
        };
    }

    public static <T> Arg<T> optional(final Arg<T> argument, T defaultValue) {
        if (argument == null) {
            throw new NullPointerException("argument");
        }
        if (argument.isGreedy()) {
            throw new IllegalArgumentException("greedy argument cannot be optional");
        }
        return new Arg<T>(argument.name(), false, true, defaultValue) {
            @Override
            T parse(String input, PlayerResolver players) throws ArgumentException {
                return argument.parse(input, players);
            }

            @Override
            boolean isCatchAll() {
                return argument.isCatchAll();
            }

            @Override
            List<String> suggest(CommandSender sender, String prefix, PlayerResolver players) {
                return argument.suggest(sender, prefix, players);
            }
        };
    }

    /** 边界为无穷时降级为单侧描述，避免把 MIN/MAX_VALUE 糊到玩家脸上。 */
    private static String range(String minimum, String maximum) {
        if (minimum != null && maximum != null) {
            return minimum + ".." + maximum;
        }
        if (minimum != null) {
            return "≥ " + minimum;
        }
        return "≤ " + maximum;
    }

    private static <E extends Enum<E>> List<String> names(Class<E> type) {
        List<String> names = new ArrayList<String>();
        for (E value : type.getEnumConstants()) {
            names.add(value.name().toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private static List<String> safeSuggestions(List<String> values, String prefix) {
        return values == null ? Collections.<String>emptyList() : matching(values, prefix);
    }

    private static List<String> matching(List<String> values, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<String>();
        for (String value : values) {
            if (value != null && value.toLowerCase(Locale.ROOT).startsWith(normalized)) {
                matches.add(value);
            }
        }
        Collections.sort(matches, String.CASE_INSENSITIVE_ORDER);
        return matches;
    }
}
