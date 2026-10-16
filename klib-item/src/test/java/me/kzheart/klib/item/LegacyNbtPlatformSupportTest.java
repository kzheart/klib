package me.kzheart.klib.item;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyNbtPlatformSupportTest {
    @Test
    void classPresenceDoesNotMakeAnUnknownServerRevisionSupported() {
        assertFalse(LegacyNbtItemTagBridge.supportsPlatform(DirectApplyItem.class, UnknownVersion.class));
    }

    @Test
    void supportedOldAndPdcEraRevisionsRetainLegacyAccessAndMigration() {
        assertTrue(LegacyNbtItemTagBridge.supportsPlatform(DirectApplyItem.class, LegacyVersion.class));
        assertTrue(LegacyNbtItemTagBridge.supportsPlatform(DirectApplyItem.class, ModernKnownVersion.class));
    }

    @Test
    void missingDirectApplyOrVersionDetectionApiIsUnavailable() {
        assertFalse(LegacyNbtItemTagBridge.supportsPlatform(ReadOnlyItem.class, LegacyVersion.class));
        assertFalse(LegacyNbtItemTagBridge.supportsPlatform(DirectApplyItem.class, NoVersionApi.class));
    }

    @Test
    void incompatiblePlatformLinkageIsUnavailable() {
        assertFalse(LegacyNbtItemTagBridge.supportsPlatform(DirectApplyItem.class, IncompatibleVersion.class));
    }

    @Test
    void unexpectedDetectionFailureIsNotSilentlyHidden() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> LegacyNbtItemTagBridge.supportsPlatform(DirectApplyItem.class, BrokenVersion.class));
        assertSame(BrokenVersion.FAILURE, error.getCause());
    }

    public static final class DirectApplyItem {
        public DirectApplyItem(ItemStack item, boolean directApply) { }
    }

    public static final class ReadOnlyItem {
        public ReadOnlyItem(ItemStack item) { }
    }

    public enum UnknownVersion {
        UNKNOWN;
        public static UnknownVersion getVersion() { return UNKNOWN; }
    }

    public enum LegacyVersion {
        MC1_12_R1;
        public static LegacyVersion getVersion() { return MC1_12_R1; }
    }

    public enum ModernKnownVersion {
        MC1_20_R4;
        public static ModernKnownVersion getVersion() { return MC1_20_R4; }
    }

    public enum NoVersionApi { UNKNOWN }

    public enum IncompatibleVersion {
        UNKNOWN;
        public static IncompatibleVersion getVersion() { throw new NoClassDefFoundError("missing platform class"); }
    }

    public enum BrokenVersion {
        UNKNOWN;
        static final RuntimeException FAILURE = new IllegalArgumentException("unexpected detector failure");
        public static BrokenVersion getVersion() { throw FAILURE; }
    }
}
