package com.etherstories.link;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #6 回归：单件重建问题只能降级对应类型的拆包，不能停掉全部容器。
 */
class ContainerSupportRegressionTest {

    @BeforeEach
    @AfterEach
    void reset() {
        ContainerSupport.resetForTests();
    }

    @Test
    void packageFailureOnlyDegradesPackageSplitting() {
        ContainerSupport.configure("on");
        assertTrue(ContainerSupport.splittable("minecraft:shulker_box"));
        assertTrue(ContainerSupport.splittable("create:package"));

        ContainerSupport.degradeToWhole("create:package", "RX #12 create:package 构建过慢 80ms");

        assertTrue(ContainerSupport.allow("minecraft:iron_ingot"));
        assertTrue(ContainerSupport.allow("minecraft:shulker_box"));
        assertTrue(ContainerSupport.allow("create:package"));
        assertTrue(ContainerSupport.splittable("minecraft:shulker_box"));
        assertFalse(ContainerSupport.splittable("create:package"));
        assertTrue(ContainerSupport.lines().stream().anyMatch(s -> s.contains("包裹类已改走整包")));
    }

    @Test
    void genericFailureDoesNotDisablePackages() {
        ContainerSupport.configure("on");

        ContainerSupport.degradeToWhole("minecraft:shulker_box",
                "RX #13 minecraft:shulker_box 构建失败");

        assertTrue(ContainerSupport.allow("minecraft:shulker_box"));
        assertTrue(ContainerSupport.allow("create:package"));
        assertFalse(ContainerSupport.splittable("minecraft:shulker_box"));
        assertTrue(ContainerSupport.splittable("create:package"));
        assertTrue(ContainerSupport.lines().stream().anyMatch(s -> s.contains("通用容器类已改走整包")));
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
    void clearDegradeRestoresSplittingInForcedMode() {
        ContainerSupport.configure("on");
        ContainerSupport.degradeToWhole("minecraft:shulker_box", "generic slow");
        ContainerSupport.degradeToWhole("create:package", "package slow");
        ContainerSupport.clearDegrade();
        assertTrue(ContainerSupport.allow("minecraft:shulker_box"));
        assertTrue(ContainerSupport.allow("create:package"));
        assertTrue(ContainerSupport.splittable("minecraft:shulker_box"));
        assertTrue(ContainerSupport.splittable("create:package"));
    }
}
