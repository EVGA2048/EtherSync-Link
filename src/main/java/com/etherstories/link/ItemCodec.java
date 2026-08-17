package com.etherstories.link;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Base64;
import java.util.List;

public final class ItemCodec {

    private ItemCodec() {}

    public static String encode(ItemStack item) {
        if (item == null) return "";
        try {
            byte[] origin = ExtraKeys.origin(item);
            if (origin != null && origin.length > 0 && !NestedItems.containerLike(ItemKeys.id(item)))
                return Base64.getEncoder().encodeToString(origin);
            byte[] nms = ItemEnvelope.encode(item);
            if (nms != null && nms.length > 0)
                return Base64.getEncoder().encodeToString(nms);
            if (NestedItems.containerLike(ItemKeys.id(item))) {
                try {
                    JavaPlugin.getPlugin(ESLinkPlugin.class).getLogger()
                            .warning("NMS 保存失败，容器不发送: " + ItemKeys.id(item));
                } catch (Throwable ignored) {
                }
                return "";
            }
            return Base64.getEncoder().encodeToString(item.serializeAsBytes());
        } catch (Throwable t) {
            return "";
        }
    }

    /** 本服能生成的真物品；对不上返回 null（不要用石头冒充） */
    public static ItemStack tryDecode(byte[] blob, String itemKey, int amount) {
        return tryDecode(blob, itemKey, amount, null);
    }

    public static ItemStack tryDecode(byte[] blob, String itemKey, int amount, String nestedKeys) {
        if (!NestedItems.safeToDecode(itemKey, nestedKeys, blob)) return null;
        ItemStack st = null;
        if (blob != null && blob.length > 0 && !NestedItems.skipBlob(itemKey, nestedKeys, blob)) {
            st = ItemEnvelope.ours(blob)
                    ? ItemEnvelope.decode(blob, nestedKeys)
                    : ItemNbt.load(blob);
            if (!ItemKeys.real(st) && !ItemNbt.ours(blob) && !ItemEnvelope.ours(blob)) {
                try {
                    st = ItemStack.deserializeBytes(blob);
                } catch (Throwable ignored) {
                }
            }
        }
        if (ItemKeys.real(st) && itemKey != null && !itemKey.isBlank()) {
            String got = ItemKeys.id(st);
            if (ItemKeys.usable(got) && !ItemKeys.same(got, itemKey)) st = null;
        }
        if (ItemKeys.real(st) && NestedItems.hasBadInner(st)) st = null;
        if (NestedItems.emptiedContainer(st, itemKey, nestedKeys)) st = null;
        if (!ItemKeys.real(st)) {
            if (NestedItems.containerLike(itemKey)) return null;
            // 富快照解码失败时不要造一个空物品冒充成功，直接退回发送端。
            if ((ItemNbt.ours(blob) && ItemNbt.rich(blob))
                    || (ItemEnvelope.ours(blob) && ItemEnvelope.rich(blob))) return null;
            st = ItemKeys.create(itemKey, amount);
        }
        if (!ItemKeys.real(st)) {
            Material mat = match(itemKey);
            if (mat == null) return null;
            st = new ItemStack(mat);
        }
        if (ExtraKeys.refuse() && ExtraKeys.firstUnknown(nestedKeys) != null) return null;
        st = ExtraKeys.reconcile(st, blob, nestedKeys);
        if (!ItemKeys.real(st)) return null;
        int max = Math.max(1, st.getMaxStackSize());
        st.setAmount(Math.max(1, Math.min(amount <= 0 ? 1 : amount, max)));
        return st;
    }

    public static String whyNot(byte[] blob, String itemKey, String nestedKeys) {
        try {
            if (!NestedItems.safeToDecode(itemKey, nestedKeys, blob))
                return "缺 " + NestedItems.firstMissing(nestedKeys, itemKey);
            ItemStack st = null;
            if (blob != null && blob.length > 0) {
                st = ItemEnvelope.ours(blob)
                        ? ItemEnvelope.decode(blob, nestedKeys)
                        : ItemNbt.load(blob);
                if (!ItemKeys.real(st) && !ItemNbt.ours(blob) && !ItemEnvelope.ours(blob)) {
                    try {
                        st = ItemStack.deserializeBytes(blob);
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (ItemKeys.real(st)) {
                String got = ItemKeys.id(st);
                if (ItemKeys.usable(got) && itemKey != null && !ItemKeys.same(got, itemKey))
                    return "还原成 " + got;
                if (NestedItems.hasBadInner(st)) return "内含损坏";
                if (NestedItems.emptiedContainer(st, itemKey, nestedKeys))
                    return "内含丢失 · 声明 " + NestedItems.split(nestedKeys).size()
                            + " 项 · 还原后组件 " + NestedItems.componentSummary(st);
            } else if (ItemEnvelope.returning(blob)) {
                return "恢复凭证未解析";
            } else if (ItemNbt.ours(blob) || ItemEnvelope.ours(blob)) {
                boolean can = ItemKeys.create(itemKey, 1) != null;
                return (ItemEnvelope.ours(blob) ? "EST" : ItemNbt.kind(blob))
                        + "还原失败 注册表" + (can ? "有" : "无") + " " + itemKey;
            } else if (NestedItems.containerLike(itemKey) && ItemKeys.create(itemKey, 1) == null) {
                return "注册表无 " + itemKey;
            }
            return ItemNbt.kind(blob);
        } catch (Throwable t) {
            return t.getClass().getSimpleName();
        }
    }

    public static boolean known(byte[] blob, String itemKey) {
        return known(blob, itemKey, null);
    }

    public static boolean known(byte[] blob, String itemKey, String nestedKeys) {
        return tryDecode(blob, itemKey, 1, nestedKeys) != null;
    }

    /** 市场图标：对不上用屏障，名字仍写原来的物品 */
    public static ItemStack icon(byte[] blob, String itemKey, String itemName, int amount) {
        return icon(blob, itemKey, itemName, amount, null);
    }

    public static ItemStack icon(byte[] blob, String itemKey, String itemName, int amount, String nestedKeys) {
        ItemStack st = tryDecode(blob, itemKey, amount, nestedKeys);
        if (st != null) return st;
        ItemStack bar = new ItemStack(Material.BARRIER, Math.max(1, Math.min(amount, 64)));
        ItemMeta meta = bar.getItemMeta();
        if (meta != null) {
            String title = (itemName == null || itemName.isBlank()) ? itemKey : itemName;
            meta.setDisplayName(ColorUtil.colorize("&c" + title));
            String miss = NestedItems.firstMissing(nestedKeys, itemKey);
            boolean extra = ExtraKeys.refuse() && ExtraKeys.firstUnknown(nestedKeys) != null
                    && ItemKeys.create(itemKey, 1) != null;
            boolean inner = !extra && miss != null && itemKey != null && !miss.equalsIgnoreCase(itemKey);
            meta.setLore(List.of(
                    ColorUtil.colorize(extra ? "&c本服缺这个附魔/属性"
                            : inner ? "&c里面有本服没有的物品" : "&c本服没有此物品"),
                    ColorUtil.colorize("&8缺: " + (miss == null ? itemKey : miss)),
                    ColorUtil.colorize("&8" + (itemKey == null ? "" : itemKey)),
                    ColorUtil.colorize("&7不会变成紫黑块，也买不了")));
            bar.setItemMeta(meta);
        }
        return bar;
    }

    public static ItemStack decode(byte[] blob, String itemKey, int amount) {
        return decode(blob, itemKey, amount, null);
    }

    public static ItemStack decode(byte[] blob, String itemKey, int amount, String nestedKeys) {
        ItemStack st = tryDecode(blob, itemKey, amount, nestedKeys);
        return st != null ? st : icon(blob, itemKey, itemKey, amount, nestedKeys);
    }

    public static String display(ItemStack item) {
        if (item == null) return "?";
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName())
            return ChatColor.stripColor(item.getItemMeta().getDisplayName());
        if (item.hasItemMeta() && item.getItemMeta().hasItemName())
            return ChatColor.stripColor(item.getItemMeta().getItemName());
        String key = ItemKeys.id(item);
        if (key.contains(":")) {
            String path = key.substring(key.indexOf(':') + 1);
            if (path.contains("package")) return path;
            path = path.replace('_', ' ');
            if (!path.isBlank() && !path.equals("air")) return path;
        }
        return item.getType().name().toLowerCase().replace('_', ' ');
    }

    private static Material match(String itemKey) {
        if (itemKey == null || itemKey.isBlank()) return null;
        Material full = Material.matchMaterial(itemKey);
        if (full != null && !full.isAir()) return full;
        String key = itemKey.contains(":") ? itemKey.substring(itemKey.indexOf(':') + 1) : itemKey;
        Material shortKey = Material.matchMaterial(key);
        if (shortKey == null || shortKey.isAir()) return null;
        return shortKey;
    }
}
