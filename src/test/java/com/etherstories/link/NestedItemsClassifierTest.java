package com.etherstories.link;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NestedItemsClassifierTest {

    @Test
    void containerLikeCoversIssueSixItems() {
        assertTrue(NestedItems.containerLike("minecraft:shulker_box"));
        assertTrue(NestedItems.containerLike("minecraft:white_shulker_box"));
        assertTrue(NestedItems.containerLike("create:package"));
        assertTrue(NestedItems.containerLike("create:cardboard_package_12x12"));
        assertFalse(NestedItems.containerLike("minecraft:iron_ingot"));
        assertFalse(NestedItems.containerLike("create:packager"));
    }

    @Test
    void createPackagesExcludeMachines() {
        assertTrue(NestedItems.isCreatePackage("create:package"));
        assertTrue(NestedItems.isCreatePackage("create:rare_cardboard_package"));
        assertFalse(NestedItems.isCreatePackage("create:packager"));
        assertFalse(NestedItems.isCreatePackage("create:package_filter"));
        assertFalse(NestedItems.isCreatePackage("minecraft:shulker_box"));
    }

    @Test
    void fluidPackagesAreNotSplitByContents() {
        assertTrue(NestedItems.fluidPackage("create:fluid_package"));
        assertFalse(NestedItems.fluidPackage("create:package"));
    }

    @Test
    void emptyContentsIgnoresSelfKeyOnly() {
        assertTrue(NestedItems.emptyContents("minecraft:shulker_box", ""));
        assertTrue(NestedItems.emptyContents("minecraft:shulker_box", "minecraft:shulker_box"));
        assertFalse(NestedItems.emptyContents("minecraft:shulker_box",
                "minecraft:shulker_box,minecraft:stone"));
        assertFalse(NestedItems.emptyContents("minecraft:iron_ingot", ""));
    }
}
