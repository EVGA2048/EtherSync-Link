package com.etherstories.link;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemSnapshotFormatTest {

    @ParameterizedTest
    @CsvSource({
            "esn0.bin, ESN0, true, false, false",
            "esn1.bin, ESN1, true, true, false",
            "esn2.bin, ESN2, true, true, false",
            "esn3.bin, ESN3, true, true, true",
            "esn4.bin, ESN4, true, true, false",
            "esn5.bin, ESN5, true, true, true",
            "esn6.bin, ESN6, true, true, true"
    })
    void goldenMagicIsClassified(String file, String kind, boolean ours, boolean rich, boolean packed)
            throws IOException {
        byte[] blob = fixture(file);
        assertEquals(kind, ItemNbt.kind(blob));
        assertEquals(ours, ItemNbt.ours(blob));
        assertEquals(rich, ItemNbt.rich(blob));
        assertEquals(packed, ItemNbt.packed(blob));
    }

    @Test
    void unknownOrEmptyBlobsStaySafe() {
        assertEquals("empty", ItemNbt.kind(null));
        assertEquals("empty", ItemNbt.kind(new byte[0]));
        assertEquals("raw/4", ItemNbt.kind(new byte[]{1, 2, 3, 4}));
        assertFalse(ItemNbt.ours(new byte[]{1, 2, 3, 4, 5}));
        assertFalse(ItemNbt.rich(fixtureOr("esn0.bin", new byte[]{'E', 'S', 'N', '0', 0})));
    }

    @Test
    void envelopesUseOwnMagic() {
        byte[] est1 = new byte[]{'E', 'S', 'T', '1', 0};
        byte[] esr1 = new byte[]{'E', 'S', 'R', '1', 0};
        assertTrue(ItemEnvelope.ours(est1));
        assertTrue(ItemEnvelope.ours(esr1));
        assertTrue(ItemEnvelope.returning(esr1));
        assertFalse(ItemEnvelope.returning(est1));
        assertFalse(ItemEnvelope.ours(new byte[]{'E', 'S', 'N', '1', 0}));
    }

    private static byte[] fixture(String name) throws IOException {
        return Files.readAllBytes(Path.of("src/test/resources/snapshots", name));
    }

    private static byte[] fixtureOr(String name, byte[] fallback) {
        try {
            return fixture(name);
        } catch (IOException e) {
            return fallback;
        }
    }
}
