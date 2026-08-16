package com.etherstories.link;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Arclight / Forge 模组物品：用注册名，不用 Material 枚举、不用数字 ID。 */
public final class ItemKeys {

    /** 查不到的注册名同样入缓存：模组包里一次全表扫描要秒级，绝不能每次都扫。 */
    private static final Object ABSENT = new Object();
    private static final Map<String, Object> ITEM_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Object> STACK_CACHE = new ConcurrentHashMap<>();
    private static volatile Map<String, Object> registryIndex;

    private ItemKeys() {}

    public static int indexedItems() {
        Map<String, Object> idx = registryIndex;
        return idx == null ? 0 : idx.size();
    }

    /** 本服所有物品注册名，发布给别的服做发货前置检查。 */
    public static java.util.Collection<String> allKeys() {
        return index().keySet();
    }

    /** 提前把索引建好，别让第一次真实收发去付这份钱。 */
    public static void warm() {
        index();
    }

    public static String firstMatching(java.util.function.Predicate<String> want) {
        for (String key : index().keySet()) {
            if (want.test(key)) return key;
        }
        return null;
    }

    public static String id(ItemStack item) {
        if (item == null) return "";
        String nms = nmsId(item);
        if (usable(nms)) return nms;
        if (item.getType() == null || item.getType().isAir()) return nms == null ? "" : nms;
        NamespacedKey k = item.getType().getKey();
        return k.getNamespace() + ":" + k.getKey();
    }

    public static boolean real(ItemStack item) {
        if (item == null) return false;
        if (item.getType() != null && !item.getType().isAir()) return true;
        return usable(nmsId(item));
    }

    public static boolean same(String a, String b) {
        if (a == null || b == null) return false;
        return a.equalsIgnoreCase(b);
    }

    /** 注册表里有这个物品（模组纸箱分尺寸也能认），不要求 Bukkit Material。 */
    public static boolean present(String key) {
        if (!usable(key)) return false;
        if (create(key, 1) != null) return true;
        Object item = itemByKey(key);
        return item != null && !isAirItem(item);
    }

    public static ItemStack create(String key, int amount) {
        if (!usable(key)) return null;
        Object cached = STACK_CACHE.get(key);
        if (cached == ABSENT) return null;
        if (cached instanceof ItemStack tpl) {
            ItemStack st = tpl.clone();
            st.setAmount(clamp(st, amount));
            return st;
        }
        ItemStack made = create0(key);
        STACK_CACHE.put(key, made == null ? ABSENT : made.clone());
        if (made == null) return null;
        made.setAmount(clamp(made, amount));
        return made;
    }

    private static ItemStack create0(String key) {
        if (RuntimeEnv.hybrid()) {
            ItemStack nms = createNms(key, 1);
            if (real(nms)) return nms;
        }
        ItemStack paper = createPaper(key, 1);
        if (real(paper)) return paper;
        Material mat = Material.matchMaterial(key);
        if (mat != null && !mat.isAir()) return new ItemStack(mat);
        if (!RuntimeEnv.hybrid()) {
            ItemStack nms = createNms(key, 1);
            if (real(nms)) return nms;
        }
        return null;
    }

    static boolean usable(String id) {
        return id != null && !id.isBlank() && !id.equalsIgnoreCase("minecraft:air");
    }

    private static int clamp(ItemStack st, int amount) {
        int max = Math.max(1, st.getMaxStackSize());
        return Math.max(1, Math.min(amount <= 0 ? 1 : amount, max));
    }

    private static String nmsId(ItemStack bukkit) {
        try {
            Object nms = asNms(bukkit);
            if (nms == null) return null;
            Object item = nmsItem(nms);
            if (item == null) return null;
            Object rl = registryKey(item);
            return rl == null ? null : rl.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object nmsItem(Object nms) {
        for (String name : new String[]{"getItem", "getItemHolder"}) {
            try {
                Object r = Reflect.method(nms.getClass(), name).invoke(nms);
                if (r == null) continue;
                if (r.getClass().getName().contains("Holder")) {
                    try {
                        r = Reflect.method(r.getClass(), "value").invoke(r);
                    } catch (Throwable ignored) {
                    }
                }
                if (r != null) return r;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static ItemStack createNms(String key, int amount) {
        try {
            Object item = itemByKey(key);
            if (item == null || isAirItem(item)) return null;
            Object stack = newStack(item, Math.max(1, amount));
            if (stack == null) return null;
            ItemStack bukkit = asBukkit(stack);
            if (!real(bukkit)) return null;
            String got = id(bukkit);
            if (usable(got) && !same(got, key)) return null;
            bukkit.setAmount(clamp(bukkit, amount));
            return bukkit;
        } catch (Throwable t) {
            return null;
        }
    }

    static Object itemByKey(String key) {
        if (!usable(key)) return null;
        Object cached = ITEM_CACHE.get(key);
        if (cached == ABSENT) return null;
        if (cached != null) return cached;
        Object found;
        try {
            Object rl = resourceLocation(key);
            found = rl == null ? null : registryGet(rl, key);
        } catch (Throwable t) {
            found = null;
        }
        ITEM_CACHE.put(key, found == null ? ABSENT : found);
        return found;
    }

    private static ItemStack createPaper(String key, int amount) {
        try {
            NamespacedKey nk = NamespacedKey.fromString(key);
            if (nk == null) return null;
            Object reg = Class.forName("org.bukkit.Registry").getField("ITEM").get(null);
            Object type = invoke1(reg, "get", nk);
            if (type == null) return null;
            Object st = null;
            try {
                st = Reflect.method(type.getClass(), "createItemStack", int.class).invoke(type, Math.max(1, amount));
            } catch (Throwable ignored) {
            }
            return st instanceof ItemStack s ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object newStack(Object item, int amount) {
        try {
            Object def = Reflect.method(item.getClass(), "getDefaultInstance").invoke(item);
            if (def != null) {
                try {
                    Reflect.method(def.getClass(), "setCount", int.class).invoke(def, amount);
                } catch (Throwable ignored) {
                }
                return def;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> nmsStack = Class.forName("net.minecraft.world.item.ItemStack");
            for (var c : nmsStack.getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 2 && p[1] == int.class && p[0].isInstance(item)) {
                    return c.newInstance(item, amount);
                }
            }
            for (var c : nmsStack.getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 1 && p[0].isInstance(item)) {
                    Object st = c.newInstance(item);
                    try {
                        Reflect.method(st.getClass(), "setCount", int.class).invoke(st, amount);
                    } catch (Throwable ignored) {
                    }
                    return st;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object resourceLocation(String key) throws Exception {
        Class<?> rl = Class.forName("net.minecraft.resources.ResourceLocation");
        try {
            return Reflect.method(rl, "parse", String.class).invoke(null, key);
        } catch (Throwable ignored) {
        }
        try {
            return Reflect.method(rl, "tryParse", String.class).invoke(null, key);
        } catch (Throwable ignored) {
        }
        String ns = "minecraft";
        String path = key;
        int i = key.indexOf(':');
        if (i > 0) {
            ns = key.substring(0, i);
            path = key.substring(i + 1);
        }
        try {
            return rl.getConstructor(String.class, String.class).newInstance(ns, path);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object registryGet(Object rl, String key) {
        Object fromVanilla = fromRegistry(itemRegistry(), rl);
        if (fromVanilla != null && !isAirItem(fromVanilla)) return fromVanilla;
        Object forge = forgeGet(rl);
        if (forge != null && !isAirItem(forge)) return forge;
        Object indexed = index().get(key.toLowerCase(Locale.ROOT));
        return indexed != null && !isAirItem(indexed) ? indexed : null;
    }

    /** 整表只遍历一次建索引。逐次全表扫描在模组包里能卡住主线程好几秒。 */
    private static Map<String, Object> index() {
        Map<String, Object> idx = registryIndex;
        if (idx != null) return idx;
        synchronized (ItemKeys.class) {
            if (registryIndex != null) return registryIndex;
            Map<String, Object> built = new HashMap<>();
            indexInto(built, itemRegistry());
            // 原版表读全了就别再扫一遍 Forge 表，那是同一批物品，白花一倍时间。
            if (built.isEmpty()) {
                for (String cls : new String[]{
                        "net.neoforged.neoforge.registries.ForgeRegistries",
                        "net.minecraftforge.registries.ForgeRegistries"
                }) {
                    try {
                        indexInto(built, Class.forName(cls).getField("ITEMS").get(null));
                    } catch (Throwable ignored) {
                    }
                    if (!built.isEmpty()) break;
                }
            }
            registryIndex = Map.copyOf(built);
            return registryIndex;
        }
    }

    private static void indexInto(Map<String, Object> idx, Object reg) {
        if (reg == null) return;
        if (indexByEntries(idx, reg)) return;
        if (!(reg instanceof Iterable<?> it)) return;
        Method getKey = keyMethod(reg);
        try {
            for (Object o : it) {
                Object item = o instanceof java.util.Map.Entry<?, ?> e ? e.getValue() : o;
                item = unwrapHolder(item);
                if (item == null) continue;
                Object rl = null;
                if (getKey != null) {
                    try {
                        rl = getKey.invoke(reg, item);
                    } catch (Throwable ignored) {
                    }
                }
                if (rl == null) rl = registryKey(item);
                if (rl == null) continue;
                idx.putIfAbsent(rl.toString().toLowerCase(Locale.ROOT), item);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 注册表自己就带 key→物品 的映射，正着读一遍就完事。
     * 反过来对每个物品调 getKey(item)、失败再走 builtInRegistryHolder 三级反射，
     * 在一万七千项的模组包里要跑五十多秒，还会连带把主线程拖住。
     */
    private static boolean indexByEntries(Map<String, Object> idx, Object reg) {
        Object entries;
        try {
            entries = Reflect.method(reg.getClass(), "entrySet").invoke(reg);
        } catch (Throwable t) {
            return false;
        }
        if (!(entries instanceof java.util.Set<?> set) || set.isEmpty()) return false;
        Method location = null;
        int added = 0;
        for (Object o : set) {
            if (!(o instanceof java.util.Map.Entry<?, ?> e)) continue;
            Object item = unwrapHolder(e.getValue());
            if (item == null) continue;
            Object rk = e.getKey();
            if (rk == null) continue;
            if (location == null && !(rk instanceof String)) {
                try {
                    location = Reflect.method(rk.getClass(), "location");
                } catch (Throwable ignored) {
                }
            }
            Object rl = rk;
            if (location != null) {
                try {
                    Object v = location.invoke(rk);
                    if (v != null) rl = v;
                } catch (Throwable ignored) {
                }
            }
            String key = rl.toString().toLowerCase(Locale.ROOT);
            // ResourceKey.toString() 长这样：ResourceKey[minecraft:item / minecraft:stone]。
            // 没能剥出 location 就整条路放弃，回退到老办法，别往索引里塞垃圾键。
            if (!usable(key) || key.indexOf(' ') >= 0 || key.indexOf('[') >= 0) return false;
            idx.putIfAbsent(key, item);
            added++;
        }
        return added > 0;
    }

    private static Method keyMethod(Object reg) {
        if (reg == null) return null;
        for (Method m : Reflect.methods(reg.getClass())) {
            if (!m.getName().equals("getKey") || m.getParameterCount() != 1) continue;
            if (m.getParameterTypes()[0].isPrimitive()) continue;
            return m;
        }
        return null;
    }

    private static Object fromRegistry(Object reg, Object rl) {
        if (reg == null) return null;
        for (String name : new String[]{"getOptional", "getValue", "get", "getValueOrThrow"}) {
            Object r = invoke1(reg, name, rl);
            if (r instanceof Optional<?> o) r = o.orElse(null);
            r = unwrapHolder(r);
            if (r != null) return r;
        }
        return null;
    }

    private static volatile Object itemRegistry;
    private static volatile Method itemRegistryKey;
    private static volatile boolean itemRegistryProbed;

    static Object itemRegistry() {
        if (itemRegistryProbed) return itemRegistry;
        synchronized (ItemKeys.class) {
            if (!itemRegistryProbed) {
                try {
                    itemRegistry = Class.forName("net.minecraft.core.registries.BuiltInRegistries")
                            .getField("ITEM").get(null);
                } catch (Throwable ignored) {
                    itemRegistry = null;
                }
                itemRegistryKey = keyMethod(itemRegistry);
                itemRegistryProbed = true;
            }
        }
        return itemRegistry;
    }

    private static Object forgeGet(Object rl) {
        for (String cls : new String[]{
                "net.neoforged.neoforge.registries.ForgeRegistries",
                "net.minecraftforge.registries.ForgeRegistries"
        }) {
            try {
                Object items = Class.forName(cls).getField("ITEMS").get(null);
                Object r = invoke1(items, "getValue", rl);
                if (r == null) r = invoke1(items, "get", rl);
                if (r == null) r = invoke1(items, "getDelegate", rl);
                r = unwrapHolder(r);
                if (r != null) return r;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object registryKey(Object item) {
        Object reg = itemRegistry();
        Method direct = itemRegistryKey;
        if (reg != null && direct != null) {
            try {
                Object rl = direct.invoke(reg, item);
                if (rl != null) return rl;
            } catch (Throwable ignored) {
            }
        }
        Object rl = invoke1(reg, "getKey", item);
        if (rl != null) return rl;
        for (String cls : new String[]{
                "net.neoforged.neoforge.registries.ForgeRegistries",
                "net.minecraftforge.registries.ForgeRegistries"
        }) {
            try {
                Object items = Class.forName(cls).getField("ITEMS").get(null);
                rl = invoke1(items, "getKey", item);
                if (rl != null) return rl;
            } catch (Throwable ignored) {
            }
        }
        try {
            Object holder = Reflect.method(item.getClass(), "builtInRegistryHolder").invoke(item);
            Object key = Reflect.method(holder.getClass(), "key").invoke(holder);
            return Reflect.method(key.getClass(), "location").invoke(key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isAirItem(Object item) {
        try {
            Object rl = registryKey(item);
            return rl != null && "minecraft:air".equalsIgnoreCase(rl.toString());
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object unwrapHolder(Object r) {
        if (r == null) return null;
        if (r.getClass().getName().contains("Holder")) {
            try {
                return Reflect.method(r.getClass(), "value").invoke(r);
            } catch (Throwable ignored) {
            }
        }
        return r;
    }

    private static Object invoke1(Object target, String name, Object arg) {
        if (target == null || arg == null) return null;
        for (Method m : Reflect.methods(target.getClass())) {
            if (!m.getName().equals(name) || m.getParameterCount() != 1) continue;
            if (!m.getParameterTypes()[0].isInstance(arg)
                    && !m.getParameterTypes()[0].isAssignableFrom(arg.getClass())) continue;
            try {
                return m.invoke(target, arg);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    static Object nmsOf(ItemStack bukkit) throws Exception {
        return asNms(bukkit);
    }

    static ItemStack fromNms(Object nms) throws Exception {
        return asBukkit(nms);
    }

    private static Object asNms(ItemStack bukkit) throws Exception {
        try {
            var f = bukkit.getClass().getDeclaredField("handle");
            f.setAccessible(true);
            Object h = f.get(bukkit);
            if (h != null) return h;
        } catch (Throwable ignored) {
        }
        Class<?> craft = craftItem();
        return Reflect.method(craft, "asNMSCopy", ItemStack.class).invoke(null, bukkit);
    }

    private static ItemStack asBukkit(Object nms) throws Exception {
        Class<?> craft = craftItem();
        Class<?> nmsStack = Class.forName("net.minecraft.world.item.ItemStack");
        for (String name : new String[]{"asCraftMirror", "asCraftCopy", "asBukkitCopy"}) {
            try {
                Object r = Reflect.method(craft, name, nmsStack).invoke(null, nms);
                if (r instanceof ItemStack st && real(st)) return st;
            } catch (Throwable ignored) {
            }
        }
        for (var c : craft.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length != 1 || !p[0].isAssignableFrom(nmsStack)) continue;
            try {
                c.setAccessible(true);
                Object r = c.newInstance(nms);
                if (r instanceof ItemStack st && real(st)) return st;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Class<?> craftItem() throws ClassNotFoundException {
        for (String n : new String[]{
                "org.bukkit.craftbukkit.inventory.CraftItemStack",
                "org.bukkit.craftbukkit.v1_21_R1.inventory.CraftItemStack",
                "org.bukkit.craftbukkit.v1_21_R2.inventory.CraftItemStack"
        }) {
            try {
                return Class.forName(n);
            } catch (ClassNotFoundException ignored) {
            }
        }
        throw new ClassNotFoundException("CraftItemStack");
    }
}
