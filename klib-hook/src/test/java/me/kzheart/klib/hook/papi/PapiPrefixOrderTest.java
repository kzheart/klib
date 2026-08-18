package me.kzheart.klib.hook.papi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PapiPrefixOrderTest {

    @Test
    void longestPrefixWinsRegardlessOfRegistrationOrder() {
        PapiExpansion expansion = new PapiDsl()
                .prefixed("top_", (player, suffix) -> "short:" + suffix)
                .prefixed("top_balance_", (player, suffix) -> "long:" + suffix)
                .build();

        assertEquals("long:gold", expansion.resolve(null, "top_balance_gold"));
        assertEquals("short:rank", expansion.resolve(null, "top_rank"));
    }
}
