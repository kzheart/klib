package me.kzheart.klib.random;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeightedPoolTest {
    @Test
    void picksFollowWeightsAndSkipZeroWeightEntries() {
        WeightedPool<String> pool = WeightedPool.<String>builder()
                .add("common", 3)
                .add("never", 0)
                .add("rare", 1)
                .build();
        Random random = new Random(42L);
        Map<String, Integer> counts = new HashMap<String, Integer>();
        for (int round = 0; round < 40_000; round++) {
            counts.merge(pool.pick(random), 1, Integer::sum);
        }
        assertEquals(null, counts.get("never"));
        double ratio = counts.get("common") / (double) counts.get("rare");
        assertTrue(ratio > 2.8 && ratio < 3.2, "ratio " + ratio);
        assertEquals(0.25, pool.chance(2), 1e-9);
    }

    @Test
    void invalidWeightsFailAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> WeightedPool.of(Arrays.asList("a", "b"), value -> 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> WeightedPool.<String>builder().add("a", -1));
        assertThrows(IllegalArgumentException.class,
                () -> WeightedPool.<String>builder().add("a", Double.NaN));
    }

    @Test
    void emptyPoolIsAllowedButCannotPick() {
        WeightedPool<String> pool = WeightedPool.of(Collections.<String>emptyList(), value -> 1.0);
        assertTrue(pool.isEmpty());
        assertThrows(IllegalStateException.class, pool::pick);
        assertEquals(Collections.emptyList(), pool.pickDistinct(3));
    }

    @Test
    void pickDistinctNeverRepeatsAndStopsAtPositiveEntries() {
        WeightedPool<String> pool = WeightedPool.<String>builder()
                .add("a", 1).add("b", 5).add("zero", 0).add("c", 2)
                .build();
        for (int seed = 0; seed < 200; seed++) {
            List<String> picked = pool.pickDistinct(10, new Random(seed));
            assertEquals(3, picked.size());
            assertEquals(new HashSet<String>(Arrays.asList("a", "b", "c")), new HashSet<String>(picked));
        }
        assertEquals(2, pool.pickDistinct(2, new Random(1L)).size());
    }
}
