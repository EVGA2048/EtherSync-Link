package com.etherstories.link;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class LinkHolder implements InventoryHolder {
    public final String kind;
    public Inventory inv;

    public LinkHolder(String kind) {
        this.kind = kind;
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }
}
