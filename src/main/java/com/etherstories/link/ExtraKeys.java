package com.etherstories.link;

import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 模组附魔 / 属性：对面没注册表就补不回来。
 * TX 把 id 写进 nested_keys（e: / a:）；RX 丢掉的写 lore，原 NBT 塞 PDC，带回原服再解。
 */
public final class ExtraKeys {

    private static final NamespacedKey ORIGIN = new NamespacedKey("eslink", "origin_blob");
    private static final NamespacedKey LOST = new NamespacedKey("eslink", "lost_extra");
    private static final NamespacedKey TOKEN = new NamespacedKey("eslink", "restore_token");
    private static final String HEAD = "[ESLink] 本服缺附魔/属性";
    private static final String BACK = "带回原服可恢复";

    private ExtraKeys() {}

    public static boolean tagged(String key) {
        if (key == null) return false;
        String s = key.trim();
        return s.regionMatches(true, 0, "e:", 0, 2) || s.regionMatches(true, 0, "a:", 0, 2);
    }

    public static void addTo(ItemStack item, Set<String> keys) {
        if (item == null || keys == null) return;
        if (NestedItems.containerLike(ItemKeys.id(item))) return;
        for (String id : enchants(item)) keys.add("e:" + id);
        for (String id : attributes(item)) keys.add("a:" + id);
    }

    public static boolean refuse() {
        try {
            String m = JavaPlugin.getPlugin(ESLinkPlugin.class)
                    .getConfig().getString("chest.unknown-extra", "deliver");
            return m != null && m.equalsIgnoreCase("refuse");
        } catch (Throwable t) {
            return false;
        }
    }

    public static String firstUnknown(String csv) {
        for (Tagged t : declared(csv)) {
            if (!known(t)) return t.id();
        }
        return null;
    }

    public static ItemStack reconcile(ItemStack item, byte[] blob, String csv) {
        if (!ItemKeys.real(item)) return item;
        List<String> lost = new ArrayList<>();
        Set<String> have = live(item);
        for (Tagged t : declared(csv)) {
            if (have.contains(t.id()) || known(t)) continue;
            lost.add(t.id());
        }
        if (lost.isEmpty()) {
            clean(item);
            return item;
        }
        if (refuse()) return null;
        stamp(item, blob, lost);
        return item;
    }

    public static byte[] origin(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(ORIGIN, PersistentDataType.BYTE_ARRAY);
    }

    public static UUID token(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String raw = item.getItemMeta().getPersistentDataContainer().get(TOKEN, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean proxy(ItemStack item) {
        return token(item) != null;
    }

    static List<String> enchants(ItemStack item) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        try {
            for (Enchantment e : item.getEnchantments().keySet()) addNs(keyOf(e), out);
        } catch (Throwable ignored) {
        }
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof EnchantmentStorageMeta book) {
                for (Enchantment e : book.getStoredEnchants().keySet()) addNs(keyOf(e), out);
            }
        } catch (Throwable ignored) {
        }
        walkSer(item, out, new LinkedHashSet<>(), 0);
        return new ArrayList<>(out);
    }

    static List<String> attributes(ItemStack item) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.hasAttributeModifiers()) {
                for (Attribute a : meta.getAttributeModifiers().keySet()) addNs(keyOf(a), out);
            }
        } catch (Throwable ignored) {
        }
        walkSer(item, new LinkedHashSet<>(), out, 0);
        return new ArrayList<>(out);
    }

    private static Set<String> live(ItemStack item) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.addAll(enchants(item));
        out.addAll(attributes(item));
        return out;
    }

    private record Tagged(char kind, String id) {}

    private static List<Tagged> declared(String csv) {
        List<Tagged> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        for (String p : csv.split(",")) {
            String t = p.trim();
            if (t.length() < 4) continue;
            char k = Character.toLowerCase(t.charAt(0));
            if ((k == 'e' || k == 'a') && t.charAt(1) == ':') {
                String id = t.substring(2).trim().toLowerCase(Locale.ROOT);
                if (id.contains(":")) out.add(new Tagged(k, id));
            }
        }
        return out;
    }

    private static boolean known(Tagged t) {
        return t.kind == 'e' ? knownEnchant(t.id) : knownAttribute(t.id);
    }

    private static boolean knownEnchant(String id) {
        NamespacedKey nk = NamespacedKey.fromString(id);
        if (nk == null) return false;
        try {
            if (Enchantment.getByKey(nk) != null) return true;
        } catch (Throwable ignored) {
        }
        if (registryHas("ENCHANTMENT", nk)) return true;
        return forgeHas(id, "ENCHANTMENTS");
    }

    private static boolean knownAttribute(String id) {
        NamespacedKey nk = NamespacedKey.fromString(id);
        if (nk == null) return false;
        if (registryHas("ATTRIBUTE", nk)) return true;
        return forgeHas(id, "ATTRIBUTES");
    }

    private static boolean registryHas(String field, NamespacedKey nk) {
        try {
            Object reg = Registry.class.getField(field).get(null);
            Object r = Reflect.method(reg.getClass(), "get", NamespacedKey.class).invoke(reg, nk);
            return r != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean forgeHas(String id, String field) {
        for (String cls : new String[]{
                "net.neoforged.neoforge.registries.ForgeRegistries",
                "net.minecraftforge.registries.ForgeRegistries"
        }) {
            try {
                Object reg = Class.forName(cls).getField(field).get(null);
                Object rl = resourceLocation(id);
                if (rl == null) continue;
                Object r = invoke1(reg, "getValue", rl);
                if (r == null) r = invoke1(reg, "get", rl);
                if (r instanceof Optional<?> o) r = o.orElse(null);
                if (r != null) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static Object resourceLocation(String key) {
        try {
            Class<?> rl = Class.forName("net.minecraft.resources.ResourceLocation");
            try {
                return Reflect.method(rl, "parse", String.class).invoke(null, key);
            } catch (Throwable ignored) {
            }
            return Reflect.method(rl, "tryParse", String.class).invoke(null, key);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object invoke1(Object target, String name, Object arg) {
        if (target == null || arg == null) return null;
        for (Method m : Reflect.methods(target.getClass())) {
            if (!m.getName().equals(name) || m.getParameterCount() != 1) continue;
            try {
                return m.invoke(target, arg);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void stamp(ItemStack item, byte[] blob, List<String> lost) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        List<ItemEnvelope.Escrow> escrows = ItemEnvelope.escrows(blob);
        if (!escrows.isEmpty()) {
            meta.getPersistentDataContainer().set(TOKEN, PersistentDataType.STRING,
                    escrows.get(0).token().toString());
            meta.getPersistentDataContainer().remove(ORIGIN);
        } else if (blob != null && blob.length > 0) {
            meta.getPersistentDataContainer().set(ORIGIN, PersistentDataType.BYTE_ARRAY, blob);
        }
        meta.getPersistentDataContainer().set(LOST, PersistentDataType.STRING, String.join(",", lost));
        List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
        stripStamp(lore);
        lore.add(ColorUtil.colorize("&8" + HEAD));
        int n = 0;
        for (String id : lost) {
            if (n++ >= 6) {
                lore.add(ColorUtil.colorize("&8缺 等 " + (lost.size() - 6) + " 条"));
                break;
            }
            lore.add(ColorUtil.colorize("&7缺 " + id));
        }
        lore.add(ColorUtil.colorize("&8" + BACK));
        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    private static void clean(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        var pdc = meta.getPersistentDataContainer();
        Set<NamespacedKey> keys = pdc.getKeys();
        if (keys == null || keys.isEmpty()) return;
        boolean oldMarker = false;
        for (NamespacedKey k : keys) {
            if (k.getNamespace().equalsIgnoreCase("eslink")) {
                oldMarker |= k.getKey().equalsIgnoreCase("act")
                        || k.getKey().equalsIgnoreCase("id")
                        || k.getKey().equalsIgnoreCase("data");
                pdc.remove(k);
            }
        }
        if (oldMarker) {
            // 旧版占位标识是屏障 + 假名字/lore，清掉后恢复成普通屏障。
            meta.setDisplayName(null);
            meta.setLore(null);
        } else {
            List<String> lore = meta.getLore();
            if (lore != null) {
                List<String> next = new ArrayList<>(lore);
                stripStamp(next);
                meta.setLore(next.isEmpty() ? null : next);
            }
        }
        item.setItemMeta(meta);
    }

    public static void clearProxy(ItemStack item) {
        clean(item);
    }

    public static boolean hasStamp(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        for (NamespacedKey k : pdc.getKeys()) {
            if (k.getNamespace().equalsIgnoreCase("eslink")) return true;
        }
        return false;
    }

    private static void stripStamp(List<String> lore) {
        lore.removeIf(line -> {
            String s = ChatColor.stripColor(line == null ? "" : line).trim();
            return s.startsWith("[ESLink]") || s.startsWith("缺 ") || s.startsWith(BACK);
        });
    }

    private static String keyOf(Object o) {
        if (o == null) return "";
        try {
            Object k = Reflect.method(o.getClass(), "getKey").invoke(o);
            return k == null ? "" : k.toString();
        } catch (Throwable t) {
            return String.valueOf(o);
        }
    }

    private static void addNs(String raw, Set<String> out) {
        if (raw == null) return;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (!s.contains(":") || s.startsWith("minecraft:air")) return;
        if (s.chars().noneMatch(Character::isLetter)) return;
        out.add(s);
    }

    @SuppressWarnings("unchecked")
    private static void walkSer(ItemStack item, Set<String> enchants, Set<String> attrs, int depth) {
        try {
            walkObj(item.serialize(), enchants, attrs, depth);
        } catch (Throwable ignored) {
        }
    }

    private static void walkObj(Object o, Set<String> enchants, Set<String> attrs, int depth) {
        if (o == null || depth > 6) return;
        if (o instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String k = String.valueOf(e.getKey()).toLowerCase(Locale.ROOT);
                if (k.contains("enchant")) addMapIds(e.getValue(), enchants);
                else if (k.contains("attribute")) addMapIds(e.getValue(), attrs);
                else walkObj(e.getValue(), enchants, attrs, depth + 1);
            }
            return;
        }
        if (o instanceof Iterable<?> it) {
            for (Object v : it) walkObj(v, enchants, attrs, depth + 1);
        }
    }

    private static void addMapIds(Object o, Set<String> out) {
        if (o instanceof Map<?, ?> m) {
            for (Object k : m.keySet()) addNs(String.valueOf(k), out);
            for (Object v : m.values()) addMapIds(v, out);
        } else if (o instanceof Iterable<?> it) {
            for (Object v : it) addMapIds(v, out);
        } else if (o instanceof String s) {
            addNs(s, out);
        }
    }
}
