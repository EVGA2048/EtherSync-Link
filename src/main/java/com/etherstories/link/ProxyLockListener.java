package com.etherstories.link;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.Set;

/** 降级投影只能使用、磨损和运输，不能通过工作站改写。 */
public final class ProxyLockListener implements Listener {

    private static final Set<InventoryType> LOCKED = EnumSet.of(
            InventoryType.ANVIL,
            InventoryType.GRINDSTONE,
            InventoryType.SMITHING,
            InventoryType.ENCHANTING,
            InventoryType.WORKBENCH,
            InventoryType.CRAFTING,
            InventoryType.STONECUTTER,
            InventoryType.CARTOGRAPHY
    );

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!LOCKED.contains(e.getView().getTopInventory().getType())) return;
        int hotbar = e.getHotbarButton();
        ItemStack swapped = hotbar >= 0 ? e.getWhoClicked().getInventory().getItem(hotbar) : null;
        if (ExtraKeys.proxy(e.getCurrentItem()) || ExtraKeys.proxy(e.getCursor())
                || ExtraKeys.proxy(swapped)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!LOCKED.contains(e.getView().getTopInventory().getType())) return;
        if (ExtraKeys.proxy(e.getOldCursor()) || ExtraKeys.proxy(e.getCursor()))
            e.setCancelled(true);
    }

    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        if (containsProxy(e.getInventory())) e.getInventory().setResult(null);
    }

    @EventHandler
    public void onAnvil(PrepareAnvilEvent e) {
        if (containsProxy(e.getInventory())) e.setResult(null);
    }

    @EventHandler
    public void onSmith(PrepareSmithingEvent e) {
        if (containsProxy(e.getInventory())) e.setResult(null);
    }

    private static boolean containsProxy(Inventory inventory) {
        for (ItemStack item : inventory.getContents())
            if (ExtraKeys.proxy(item)) return true;
        return false;
    }
}
