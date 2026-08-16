package com.etherstories.link;

import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 有些物品的数据是"指向本服的引用"，不是自包含的内容：
 * 地图的 map_id 指本服世界的地图编号，磁石指针记的是本服坐标，
 * Create 蓝图引用服务器上的示意图文件。
 *
 * 这类东西 NBT 传得再准也没意义——对面按同一个编号找到的是另一张图，或者什么都没有。
 * 传输层修不好，所以照发，但在物品上留一行说明，免得玩家当成 bug。
 */
public final class CrossRefs {
    private static final String TAG = "跨服引用";
    private static final String[] COMPONENTS = {
            "minecraft:map_id",
            "minecraft:lodestone_tracker",
    };
    private static final String[] KEY_HINTS = {
            "schematic",
            "blueprint",
    };

    private CrossRefs() {}

    public static boolean serverBound(ItemStack item) {
        if (!ItemKeys.real(item)) return false;
        for (String c : COMPONENTS) {
            if (DataComponents.has(item, c)) return true;
        }
        String key = ItemKeys.id(item);
        if (key == null) return false;
        String low = key.toLowerCase(Locale.ROOT);
        for (String hint : KEY_HINTS) {
            if (low.contains(hint)) return true;
        }
        return false;
    }

    /** 加一行说明。已经标过就不重复加，否则来回退几次就堆满了。 */
    public static ItemStack mark(ItemStack item, String fromServer) {
        if (!serverBound(item)) return item;
        // 加 lore 要走一遍 meta 往返，在 Arclight 上会把模组组件抹掉。
        // 蓝图这类东西身上带的正是模组数据，为一行说明把内容弄丢不值。
        if (DataComponents.modded(item)) return item;
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return item;
            List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
            for (String line : lore) {
                if (ChatColor.stripColor(line).contains(TAG)) return item;
            }
            String where = fromServer == null || fromServer.isBlank() ? "其他服务器" : fromServer;
            lore.add(ChatColor.DARK_GRAY + TAG + " · 来自" + where);
            lore.add(ChatColor.DARK_GRAY + "内容指向原服，本服可能失效");
            meta.setLore(lore);
            item.setItemMeta(meta);
        } catch (Throwable ignored) {
        }
        return item;
    }

    /** 发货前给玩家的提醒，没问题返回 null。 */
    public static String notice(ItemStack item) {
        if (!serverBound(item)) return null;
        return "这件东西的内容指向本服（地图/坐标/蓝图），发过去多半失效";
    }
}
