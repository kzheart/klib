package me.kzheart.klib.ui;

import me.kzheart.klib.lang.RichText;
import me.kzheart.klib.ui.chat.ChatPanelButton;
import me.kzheart.klib.ui.drop.InventoryAction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlatformReturnContractJavaTest {
    @Test void optionalButtonAndActionValuesStayNull() {
        assertNull(InventoryAction.take(0, 1).item());
        ChatPanelButton button = ChatPanelButton.copy(RichText.plain("label"), "copied");
        assertNull(button.id());
        assertNull(button.hover());
        assertNull(button.value());
        ChatPanelButton changed = button.hover(RichText.plain("hover")).value("value");
        assertEquals("hover", changed.hover().plainText());
        assertEquals("value", changed.value().plainText());
        assertNull(button.hover());
    }
}
