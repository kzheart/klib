package me.kzheart.klib.random;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RangeAndChanceTest {
    @Test
    void intRangeAcceptsCommonNotationsIncludingNegatives() {
        assertEquals(IntRange.of(5, 5), IntRange.parse("5"));
        assertEquals(IntRange.of(1, 5), IntRange.parse("1-5"));
        assertEquals(IntRange.of(1, 5), IntRange.parse(" 1 ~ 5 "));
        assertEquals(IntRange.of(-5, 3), IntRange.parse("-5-3"));
        assertEquals(IntRange.of(-5, -1), IntRange.parse("-5--1"));
        assertEquals(IntRange.of(-5, -1), IntRange.parse("-5~-1"));
        assertEquals(IntRange.of(2, 8), IntRange.parse("2 to 8"));
    }

    @Test
    void intRangeRejectsGarbageAndReversedBounds() {
        assertThrows(IllegalArgumentException.class, () -> IntRange.parse("a-b"));
        assertThrows(IllegalArgumentException.class, () -> IntRange.parse("5-1"));
        assertThrows(IllegalArgumentException.class, () -> IntRange.parse("1.5"));
        assertThrows(IllegalArgumentException.class, () -> IntRange.parse("99999999999"));
    }

    @Test
    void intRandomStaysInsideInclusiveBounds() {
        IntRange range = IntRange.parse("-2~2");
        Random random = new Random(7L);
        boolean sawMin = false;
        boolean sawMax = false;
        for (int round = 0; round < 1_000; round++) {
            int value = range.random(random);
            assertTrue(range.contains(value));
            sawMin |= value == -2;
            sawMax |= value == 2;
        }
        assertTrue(sawMin && sawMax);
        assertTrue(IntRange.of(Integer.MIN_VALUE, Integer.MAX_VALUE).contains(
                IntRange.of(Integer.MIN_VALUE, Integer.MAX_VALUE).random(random)));
    }

    @Test
    void doubleRangeParsesDecimals() {
        assertEquals(DoubleRange.of(0.5, 2.5), DoubleRange.parse("0.5~2.5"));
        assertEquals(DoubleRange.of(-1.5, 1), DoubleRange.parse("-1.5-1"));
        assertEquals(DoubleRange.of(3, 3), DoubleRange.parse("3"));
        assertEquals(1.0, DoubleRange.parse("1").random(new Random(1L)), 0.0);
        assertThrows(IllegalArgumentException.class, () -> DoubleRange.parse("2~1"));
    }

    @Test
    void chanceClampsBoundsAndRejectsNaN() {
        Random random = new Random(3L);
        assertFalse(Chance.percent(random, 0));
        assertFalse(Chance.percent(random, -5));
        assertTrue(Chance.percent(random, 100));
        assertTrue(Chance.ratio(random, 2));
        assertThrows(IllegalArgumentException.class, () -> Chance.percent(random, Double.NaN));
        int hits = 0;
        for (int round = 0; round < 10_000; round++) {
            if (Chance.percent(random, 25)) {
                hits++;
            }
        }
        assertTrue(hits > 2_300 && hits < 2_700, "hits " + hits);
    }
}
