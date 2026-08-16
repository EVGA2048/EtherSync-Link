package com.etherstories.link;

import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 数据组件读写。
 * Youer / Arclight 上按注册名正查 DataComponentType 会拿到 null，
 * 但组件自己的 toString() 就是注册名，所以读取直接遍历物品组件表，
 * 写入用一次性建好的反查索引，两条路都不依赖正查。
 */
public final class DataComponents {

    private static final Object ABSENT = new Object();
    private static final Map<String, Object> TYPES = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile Map<String, Object> typeIndex;

    private DataComponents() {}

    public static Object read(ItemStack item, String id) {
        if (item == null || id == null) return null;
        Object nms = nms(item);
        if (nms == null) return null;
        for (Object[] e : entries(nms)) {
            if (id.equalsIgnoreCase(String.valueOf(e[0]))) return e[1];
        }
        Object type = type(id);
        return type == null ? null : get(nms, type);
    }

    public static boolean has(ItemStack item, String id) {
        return read(item, id) != null;
    }

    /** 身上挂着非原版组件。这类物品经不起 ItemMeta 往返，Arclight 上会被剥干净。 */
    public static boolean modded(ItemStack item) {
        Object nms = nms(item);
        if (nms == null) return false;
        for (Object[] e : entries(nms)) {
            String id = String.valueOf(e[0]);
            if (!id.isEmpty() && !id.startsWith("minecraft:")) return true;
        }
        return false;
    }

    /** 组件表能不能列出来。列得出来但没这一项，才敢当"确实为空"。 */
    public static boolean readable(ItemStack item) {
        Object nms = nms(item);
        return nms != null && !entries(nms).isEmpty();
    }

    /** 写入后返回新物品；写不了返回 null，由调用方决定是否放弃。 */
    public static ItemStack write(ItemStack item, String id, Object value) {
        if (item == null || id == null || value == null) return null;
        try {
            Object nms = nms(item);
            Object type = type(id);
            if (nms == null || type == null) return null;
            if (!set(nms, type, value)) return null;
            ItemStack out = ItemKeys.fromNms(nms);
            if (!ItemKeys.real(out)) return null;
            out.setAmount(Math.max(1, item.getAmount()));
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    public static List<String> names(ItemStack item) {
        List<String> out = new ArrayList<>();
        Object nms = nms(item);
        if (nms == null) return out;
        for (Object[] e : entries(nms)) out.add(String.valueOf(e[0]));
        return out;
    }

    public static boolean typeLookupWorks() {
        return type("minecraft:container") != null;
    }

    public static int indexedTypes() {
        Map<String, Object> idx = typeIndex;
        return idx == null ? 0 : idx.size();
    }

    static Object type(String id) {
        if (id == null || id.isBlank()) return null;
        Object cached = TYPES.get(id);
        if (cached == ABSENT) return null;
        if (cached != null) return cached;
        Object found = index().get(id.toLowerCase(Locale.ROOT));
        if (found == null) found = lookup(id);
        TYPES.put(id, found == null ? ABSENT : found);
        return found;
    }

    private static Map<String, Object> index() {
        Map<String, Object> idx = typeIndex;
        if (idx != null) return idx;
        synchronized (DataComponents.class) {
            if (typeIndex != null) return typeIndex;
            Map<String, Object> built = new HashMap<>();
            try {
                Object reg = Class.forName("net.minecraft.core.registries.BuiltInRegistries")
                        .getField("DATA_COMPONENT_TYPE").get(null);
                if (reg instanceof Iterable<?> it) {
                    for (Object type : it) {
                        if (type == null) continue;
                        String name = String.valueOf(type);
                        if (name.indexOf(':') > 0) built.putIfAbsent(name.toLowerCase(Locale.ROOT), type);
                    }
                }
            } catch (Throwable ignored) {
            }
            typeIndex = Map.copyOf(built);
            return typeIndex;
        }
    }

    private static Object lookup(String id) {
        Object rl = resourceLocation(id);
        if (rl == null) return null;
        try {
            Object reg = Class.forName("net.minecraft.core.registries.BuiltInRegistries")
                    .getField("DATA_COMPONENT_TYPE").get(null);
            Object r = invoke(reg, "get", rl);
            if (r instanceof java.util.Optional<?> o) r = o.orElse(null);
            if (r != null) return unwrapHolder(r);
        } catch (Throwable ignored) {
        }
        for (String cls : new String[]{
                "net.neoforged.neoforge.registries.ForgeRegistries",
                "net.minecraftforge.registries.ForgeRegistries"
        }) {
            try {
                Object reg = Class.forName(cls).getField("DATA_COMPONENT_TYPES").get(null);
                Object r = invoke(reg, "getValue", rl);
                if (r == null) r = invoke(reg, "get", rl);
                if (r != null) return unwrapHolder(r);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 物品自带组件表：每项是 [type, value]。 */
    private static List<Object[]> entries(Object nms) {
        List<Object[]> out = new ArrayList<>();
        try {
            Object map = Reflect.method(nms.getClass(), "getComponents").invoke(nms);
            if (!(map instanceof Iterable<?> it)) return out;
            for (Object typed : it) {
                if (typed == null) continue;
                Object type = call(typed, "type");
                if (type == null) continue;
                out.add(new Object[]{type, call(typed, "value")});
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static Object call(Object target, String name) {
        try {
            return Reflect.method(target.getClass(), name).invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object get(Object nms, Object type) {
        for (Method m : Reflect.methods(nms.getClass())) {
            if (!m.getName().equals("get") || m.getParameterCount() != 1) continue;
            if (!m.getParameterTypes()[0].isInstance(type)) continue;
            try {
                return m.invoke(nms, type);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean set(Object nms, Object type, Object value) {
        for (Method m : Reflect.methods(nms.getClass())) {
            if (m.getParameterCount() != 2) continue;
            String n = m.getName();
            if (!n.equals("set") && !n.equals("setComponent")) continue;
            if (!m.getParameterTypes()[0].isInstance(type)) continue;
            try {
                m.invoke(nms, type, value);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static Object invoke(Object target, String name, Object arg) {
        if (target == null || arg == null) return null;
        for (Method m : Reflect.methods(target.getClass())) {
            if (!m.getName().equals(name) || m.getParameterCount() != 1) continue;
            if (!m.getParameterTypes()[0].isInstance(arg)) continue;
            try {
                return m.invoke(target, arg);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object unwrapHolder(Object r) {
        if (r == null) return null;
        if (r.getClass().getName().contains("Holder")) {
            Object v = call(r, "value");
            if (v != null) return v;
        }
        return r;
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

    private static Object nms(ItemStack item) {
        try {
            return ItemKeys.nmsOf(item);
        } catch (Throwable t) {
            return null;
        }
    }
}
