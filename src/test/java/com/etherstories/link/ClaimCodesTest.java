package com.etherstories.link;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimCodesTest {

    @Test
    void generateIsAlwaysSixDigits() {
        for (int i = 0; i < 32; i++) {
            String code = ClaimCodes.generate();
            assertEquals(6, code.length());
            assertTrue(ClaimCodes.plausible(code));
        }
    }

    @Test
    void normalizeKeepsDigitsOnly() {
        assertEquals("123456", ClaimCodes.normalize("123456"));
        assertEquals("123456", ClaimCodes.normalize("12-34 56"));
        assertEquals("", ClaimCodes.normalize(null));
        assertFalse(ClaimCodes.plausible("12345"));
        assertFalse(ClaimCodes.plausible("abcdef"));
    }

    @Test
    void listingHashRoundTrip() {
        assertTrue(ClaimCodes.listingOk("654321", ClaimCodes.listingHash("654321")));
        assertFalse(ClaimCodes.listingOk("654322", ClaimCodes.listingHash("654321")));
        assertFalse(ClaimCodes.listingOk("654321", ""));
    }

    @Test
    void walletHashIsBoundToUuid() {
        UUID a = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID b = UUID.fromString("22222222-2222-2222-2222-222222222222");
        String hash = ClaimCodes.walletHash(a, "000111");
        assertTrue(ClaimCodes.walletOk(a, "000111", hash));
        assertFalse(ClaimCodes.walletOk(b, "000111", hash));
        assertFalse(ClaimCodes.walletOk(a, "000112", hash));
    }
}
