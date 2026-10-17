package me.kzheart.klib.command;
import java.util.List;
@FunctionalInterface
public interface ContextualSuggestionProvider { List<String> suggest(SuggestionContext context); }
