package com.etherstories.link;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class StoreChecksumTest {

    @Test
    void rowHashCoversPayloadKeyAmountAndNested() {
        String a = Store.rowSha256("Y syn", "minecraft:stone", 16, "");
        assertEquals(a, Store.rowSha256("Y syn", "minecraft:stone", 16, ""));
        assertNotEquals(a, Store.rowSha256("Y syn", "minecraft:stone", 15, ""));
        assertNotEquals(a, Store.rowSha256("Y syn", "minecraft:cobblestone", 16, ""));
        assertNotEquals(a, Store.rowSha256("Y syn", "minecraft:stone", 16, "create:package"));
        assertNotEquals(a, Store.rowSha256("other", "minecraft:stone", 16, ""));
    }

    @Test
    void batchHashIsOrderSensitive() {
        String r1 = Store.rowSha256("a", "minecraft:stone", 1, "");
        String r2 = Store.rowSha256("b", "minecraft:dirt", 1, "");
        String ab = Store.batchSha256(List.of(r1, r2));
        assertEquals(ab, Store.batchSha256(List.of(r1, r2)));
        assertNotEquals(ab, Store.batchSha256(List.of(r2, r1)));
        assertNotEquals(ab, Store.batchSha256(List.of(r1)));
    }

    @Test
    void tamperedRowWouldFailBatchVerify() {
        String good = Store.rowSha256("blob", "create:package", 1, "minecraft:stone");
        String tampered = Store.rowSha256("blob!", "create:package", 1, "minecraft:stone");
        String batch = Store.batchSha256(List.of(good));
        assertNotEquals(batch, Store.batchSha256(List.of(tampered)));
    }
}
