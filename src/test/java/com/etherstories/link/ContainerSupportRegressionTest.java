package com.etherstories.link;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 issue #6 / 0.2.11 的容器封禁语义，以及 0.2.12 仍留下的运行时 trip。
 * 下一步会把「禁用拆包」和「全局停运」拆开，到时改这些断言。
 */
class ContainerSupportRegressionTest {

    @BeforeEach
    @AfterEach
    void reset() {
        ContainerSupport.resetForTests();
    }

    @Test
    void vanillaItemsStaySendableAfterTrip() {
        ContainerSupport.trip("RX #12 create:package 构建过慢 80ms");
        assertTrue(ContainerSupport.allow("minecraft:iron_ingot"));
        assertTrue(ContainerSupport.allow("minecraft:stone"));
    }

    @Test
    void runtimeTripStillBlocksEveryContainer() {
        assertTrue(ContainerSupport.allow("minecraft:shulker_box"));
        assertTrue(ContainerSupport.allow("create:package"));
        ContainerSupport.trip("RX #12 create:package 构建过慢 80ms");
        assertFalse(ContainerSupport.allow("minecraft:shulker_box"),
                "0.2.12 仍用 trip 一刀切；issue #6 的「牌子无反应」由此复发");
        assertFalse(ContainerSupport.allow("create:package"));
        assertTrue(ContainerSupport.blockReason("create:package").contains("熔断"));
    }

    @Test
    void configOffBlocksContainersWithoutTripping() {
        ContainerSupport.configure("off");
        assertFalse(ContainerSupport.allow("minecraft:shulker_box"));
        assertFalse(ContainerSupport.splittable("minecraft:shulker_box"));
        assertTrue(ContainerSupport.allow("minecraft:diamond"));
    }

    @Test
    void autoModeWaitsForProbeInsteadOfBouncing() {
        ContainerSupport.configure("auto");
        assertTrue(ContainerSupport.pending("minecraft:shulker_box"));
        assertTrue(ContainerSupport.pending("create:package"));
        assertFalse(ContainerSupport.pending("minecraft:iron_ingot"));
        assertFalse(ContainerSupport.splittable("minecraft:shulker_box"));
    }

    @Test
    void fluidPackagesAreNotHeldForSplitProbe() {
        assertFalse(ContainerSupport.pending("create:fluid_package"));
        assertFalse(ContainerSupport.splittable("create:fluid_package"));
        assertTrue(ContainerSupport.allow("create:fluid_package"));
    }

    @Test
    void clearTripRestoresAllow() {
        ContainerSupport.trip("自检占用主线程 2400ms");
        ContainerSupport.clearTrip();
        assertTrue(ContainerSupport.allow("minecraft:shulker_box"));
        assertTrue(ContainerSupport.allow("create:package"));
    }
}
