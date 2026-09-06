package com.etherstories.link;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class UnitsTest {

    @Test
    void codeIsStableSixChars() {
        assertEquals(6, Units.code(1).length());
        assertEquals(Units.code(42), Units.code(42));
        assertNotEquals(Units.code(1), Units.code(2));
    }

    @Test
    void orPrefersValidSerial() {
        assertEquals("AB12CD", Units.or("ab12cd", 9));
        assertEquals(Units.code(9), Units.or("short", 9));
        assertEquals(Units.code(9), Units.or(null, 9));
    }
}
