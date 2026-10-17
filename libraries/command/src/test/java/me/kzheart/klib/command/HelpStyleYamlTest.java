package me.kzheart.klib.command;

import me.kzheart.klib.command.api.CommandHelpStyle;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HelpStyleYamlTest {
    @Test void selectingAnotherPresetDoesNotApplyInactiveCustomTemplates() {
        MemoryConfiguration yaml = new MemoryConfiguration();
        yaml.set("preset", "panel");
        yaml.set("custom.header", "自定义标题");
        CommandHelpStyle style = CommandHelpStyles.parse(yaml);
        assertTrue(style.header().contains("操作面板"));
    }
    @Test void customYamlSupportsLayoutColorsAndRejectsIncompleteUsage() {
        MemoryConfiguration yaml = new MemoryConfiguration();
        yaml.set("preset", "custom");
        yaml.set("custom.header", "<gold>自定义 {command}");
        yaml.set("custom.entry", "{usage} — {description}");
        yaml.set("custom.command-color", "#e8b04a");
        CommandHelpStyle style = CommandHelpStyles.parse(yaml);
        assertEquals(0xe8b04a, style.commandColor());
        assertEquals("<gold>自定义 {command}", style.header());
        yaml.set("custom.usage", "{usage}");
        assertThrows(IllegalArgumentException.class, () -> CommandHelpStyles.parse(yaml));
    }
}
