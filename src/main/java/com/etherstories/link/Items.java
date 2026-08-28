package com.etherstories.link;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.UUID;

public final class Items {

    public static final String[] COLORS = {
            "WHITE", "LIGHT_GRAY", "GRAY", "BLACK", "BROWN", "RED", "ORANGE", "YELLOW",
            "LIME", "GREEN", "CYAN", "LIGHT_BLUE", "BLUE", "PURPLE", "MAGENTA", "PINK"
    };

    private Items() {}

    public static ItemStack wand(Plugin plugin) {
        ItemStack s = named(Material.BLAZE_ROD, "&bESLink 调试棒", List.of(
                "&7点箱子 / 红石灯 / 牌子",
                "&7左键或右键打开配置",
                "&8不必蹲下，也不用准星对准",
                "&8/link stick"));
        return tag(plugin, s, "wand", "", 0);
    }

    public static boolean isWand(Plugin plugin, ItemStack item) {
        return "wand".equals(act(plugin, item));
    }

    public static ItemStack named(Material mat, String name, List<String> lore) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        meta.setDisplayName(ColorUtil.colorize(name));
        if (lore != null) meta.setLore(lore.stream().map(ColorUtil::colorize).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    public static ItemStack glass(Material mat) {
        return named(mat, " ", null);
    }

    public static ItemStack serverMark(Models.ServerRow s, boolean online, String name, List<String> lore) {
        String color = s == null ? "LIGHT_BLUE" : s.color();
        String icon = s == null ? "TERRACOTTA" : s.icon();
        return named(serverMat(color, icon, online), name, lore);
    }

    public static Material serverMat(String color, String icon, boolean online) {
        if (!online) return Material.GRAY_CONCRETE;
        return serverMat(color, icon);
    }

    public static Material serverMat(String color, String icon) {
        String c = (color == null || color.isBlank()) ? "LIGHT_BLUE" : color.trim().toUpperCase();
        if (c.equals("GREY")) c = "GRAY";
        if (c.equals("SILVER")) c = "LIGHT_GRAY";
        boolean concrete = icon != null && icon.toUpperCase().contains("CONCRETE");
        String suffix = concrete ? "_CONCRETE" : "_TERRACOTTA";
        try {
            return Material.valueOf(c + suffix);
        } catch (Exception e) {
            return concrete ? Material.LIGHT_BLUE_CONCRETE : Material.LIGHT_BLUE_TERRACOTTA;
        }
    }

    public static String colorCn(String color) {
        if (color == null) return "浅蓝";
        return switch (color.toUpperCase()) {
            case "WHITE" -> "白";
            case "LIGHT_GRAY" -> "浅灰";
            case "GRAY" -> "灰";
            case "BLACK" -> "黑";
            case "BROWN" -> "棕";
            case "RED" -> "红";
            case "ORANGE" -> "橙";
            case "YELLOW" -> "黄";
            case "LIME" -> "黄绿";
            case "GREEN" -> "绿";
            case "CYAN" -> "青";
            case "LIGHT_BLUE" -> "浅蓝";
            case "BLUE" -> "蓝";
            case "PURPLE" -> "紫";
            case "MAGENTA" -> "品红";
            case "PINK" -> "粉";
            default -> color;
        };
    }

    public static ItemStack playerHead(UUID uuid, String name, List<String> lore) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        if (!(skull.getItemMeta() instanceof SkullMeta meta)) {
            return named(Material.PLAYER_HEAD, name, lore);
        }
        OfflinePlayer off = uuid == null ? null : Bukkit.getOfflinePlayer(uuid);
        if (off != null) meta.setOwningPlayer(off);
        meta.setDisplayName(ColorUtil.colorize(name));
        if (lore != null) meta.setLore(lore.stream().map(ColorUtil::colorize).toList());
        skull.setItemMeta(meta);
        return skull;
    }

    public static ItemStack hideExtra(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP, ItemFlag.HIDE_ATTRIBUTES);
        stack.setItemMeta(meta);
        return stack;
    }

    public static String itemKey(ItemStack item) {
        return ItemKeys.id(item);
    }

    public static boolean passFilter(String itemKey, String filter) {
        if (filter == null || filter.isBlank()) return true;
        if (itemKey == null || itemKey.isBlank()) return false;
        String f = filter.trim();
        if (!f.contains(":") || f.endsWith(":")) {
            String ns = f.endsWith(":") ? f.substring(0, f.length() - 1) : f;
            int i = itemKey.indexOf(':');
            String got = i < 0 ? itemKey : itemKey.substring(0, i);
            return got.equalsIgnoreCase(ns);
        }
        return ItemKeys.same(itemKey, f);
    }

    public static ItemStack missingMark(Plugin plugin, String itemKey) {
        return missingMark(plugin, itemKey, null);
    }

    public static ItemStack missingMark(Plugin plugin, String itemKey, String itemName) {
        String key = itemKey == null || itemKey.isBlank() ? "unknown" : itemKey;
        String title = (itemName == null || itemName.isBlank()) ? key : itemName.replace('\n', ' ');
        if (title.length() > 40) title = key;
        ItemStack bar = new ItemStack(Material.BARRIER);
        ItemMeta meta = bar.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.colorize("&c" + title));
            meta.setLore(List.of(
                    ColorUtil.colorize("&8" + key),
                    ColorUtil.colorize("&7本服没有此物品，漏斗抽不走")));
            bar.setItemMeta(meta);
        }
        return tag(plugin, bar, "lock", key, 0);
    }

    public static boolean hopperLocked(Plugin plugin, ItemStack item) {
        return "lock".equals(act(plugin, item));
    }

    public static boolean sameMissing(Plugin plugin, ItemStack item, String itemKey) {
        return hopperLocked(plugin, item) && data(plugin, item).equals(itemKey == null ? "" : itemKey);
    }

    public static ItemStack tag(Plugin plugin, ItemStack stack, String act, String data, long id) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        var pdc = meta.getPersistentDataContainer();
        pdc.set(new NamespacedKey(plugin, "act"), PersistentDataType.STRING, act == null ? "" : act);
        pdc.set(new NamespacedKey(plugin, "data"), PersistentDataType.STRING, data == null ? "" : data);
        pdc.set(new NamespacedKey(plugin, "id"), PersistentDataType.LONG, id);
        stack.setItemMeta(meta);
        return stack;
    }

    public static String act(Plugin plugin, ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return "";
        String v = stack.getItemMeta().getPersistentDataContainer()
                .get(new NamespacedKey(plugin, "act"), PersistentDataType.STRING);
        return v == null ? "" : v;
    }

    public static String data(Plugin plugin, ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return "";
        String v = stack.getItemMeta().getPersistentDataContainer()
                .get(new NamespacedKey(plugin, "data"), PersistentDataType.STRING);
        return v == null ? "" : v;
    }

    public static long id(Plugin plugin, ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return 0;
        Long v = stack.getItemMeta().getPersistentDataContainer()
                .get(new NamespacedKey(plugin, "id"), PersistentDataType.LONG);
        return v == null ? 0 : v;
    }
}
