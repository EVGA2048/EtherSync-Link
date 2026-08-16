package com.etherstories.link;

import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据组件读写。
 * 正查（注册名 → DataComponentType）在混合端经常拿不到，所以这里叠了四层兜底：
 * 1. 全量 RegistryAccess 注册表 + BuiltInRegistries 静态表的双索引；
 * 2. ResourceLocation 查不到就换成 ResourceKey 再查；
 * 3. Create 等已知模组类里的 public static DataComponentType 字段直接取；
 * 4. 物品自带的组件表（entries）反向收集：拿到过的 type 实例全部进缓存。
 * 写入在 3/4 都失败时仍会退化，但 ContainerSupport 自检会用 PackageItem.containing
 * 造一个带 create:package_contents 的模板包裹，把 4 提前喂饱。
 */
public final class DataComponents {

    private static final Object ABSENT = new Object();
    private static final Map<String, Object> TYPES = new ConcurrentHashMap<>();
    /** type 实例 → 注册名。用 identity，模组返回的就是单例。 */
    private static final Map<Object, String> STATIC_IDS =
            java.util.Collections.synchronizedMap(new IdentityHashMap<>());

    /** 已知模组类直取路径：注册名 → {类名, 字段名}。 */
    private static final Map<String, String[]> KNOWN_STATIC_TYPES = new HashMap<>();
    static {
        KNOWN_STATIC_TYPES.put("create:package_contents",
                new String[]{"com.simibubi.create.AllDataComponents", "PACKAGE_CONTENTS"});
        KNOWN_STATIC_TYPES.put("create:package_address",
                new String[]{"com.simibubi.create.AllDataComponents", "PACKAGE_ADDRESS"});
        KNOWN_STATIC_TYPES.put("create:clipboard_content",
                new String[]{"com.simibubi.create.AllDataComponents", "CLIPBOARD_CONTENT"});
    }

    private static volatile Map<String, Object> typeIndex;
    private static volatile int raIndexCount;
    private static volatile int staticIndexCount;
    private static volatile int directIndexCount;
    private static volatile String regSourceLabel = "未初始化";
    private static final Set<String> DIRECT_SOURCES = ConcurrentHashMap.newKeySet();
    private static volatile String templateLabel = "未尝试";
    private static volatile int templateTypeCount;

    private DataComponents() {}

    public static Object read(ItemStack item, String id) {
        if (item == null || id == null) return null;
        Object nms = nms(item);
        if (nms == null) return null;
        for (Object[] e : entries(nms)) {
            String name = nameOf(e[0]);
            remember(e[0], name);
            if (id.equalsIgnoreCase(name)) return e[1];
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
            String id = nameOf(e[0]);
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
            if (nms == null) return null;
            Object type = type(id);
            if (type == null) type = typeFromItem(nms, id);
            if (type == null) return null;
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
        for (Object[] e : entries(nms)) out.add(nameOf(e[0]));
        return out;
    }

    public static boolean typeLookupWorks() {
        return type("minecraft:container") != null;
    }

    public static int indexedTypes() {
        Map<String, Object> idx = typeIndex;
        return idx == null ? 0 : idx.size();
    }

    public static int raIndexedTypes() {
        Map<String, Object> idx = typeIndex;
        if (idx == null) return 0;
        return raIndexCount;
    }

    public static int staticIndexedTypes() {
        Map<String, Object> idx = typeIndex;
        if (idx == null) return 0;
        return staticIndexCount;
    }

    public static int directIndexedTypes() {
        Map<String, Object> idx = typeIndex;
        if (idx == null) return 0;
        return directIndexCount;
    }

    public static int templateTypes() {
        return templateTypeCount;
    }

    public static String registrySource() {
        return regSourceLabel;
    }

    public static String directSource() {
        return DIRECT_SOURCES.isEmpty() ? "未取到" : String.join(",", DIRECT_SOURCES);
    }

    public static String templateSource() {
        return templateLabel;
    }

    /** 这个组件 ID 能否解析出类型（索引 + 正查 + 模组类直取都会试）。 */
    public static boolean indexed(String id) {
        return type(id) != null;
    }

    /** 供 /link diag 展示的诊断行。 */
    public static List<String> diagLines() {
        List<String> out = new ArrayList<>();
        out.add("组件索引 " + indexedTypes() + " 项（全量 " + raIndexedTypes()
                + " / 静态 " + staticIndexedTypes() + " / 直取 " + directIndexedTypes() + "）");
        out.add("注册表来源 " + registrySource());
        out.add("模组类直取 " + directSource() + " · Create 模板 " + templateSource());
        for (String id : new String[]{"create:package_contents", "create:package_address", "create:clipboard_content"}) {
            out.add(id + " " + (indexed(id) ? "可解析" : "解析失败"));
        }
        return out;
    }

    static Object type(String id) {
        if (id == null || id.isBlank()) return null;
        String key = id.toLowerCase(Locale.ROOT);
        Object cached = TYPES.get(key);
        if (cached != null) return cached;
        boolean retryable = cached == ABSENT && KNOWN_STATIC_TYPES.containsKey(key);
        if (cached == ABSENT && !retryable) return null;
        Object found = index().get(key);
        if (found == null) found = knownStaticType(key);
        if (found == null) found = lookup(id);
        if (found != null) {
            TYPES.put(key, found);
        } else if (!KNOWN_STATIC_TYPES.containsKey(key)) {
            TYPES.put(key, ABSENT);
        }
        return found;
    }

    private static volatile Object dcRegCache;
    private static volatile boolean dcRegProbed;

    /** 拿到含模组组件的全量 DataComponentType 注册表；静态字段在混合端可能只有原版。 */
    private static Object dataComponentRegistry() {
        if (dcRegProbed) return dcRegCache;
        Object reg = null;
        try {
            Object ra = ItemNbt.registryAccess();
            if (ra != null) {
                Object rk = Class.forName("net.minecraft.core.registries.Registries")
                        .getField("DATA_COMPONENT_TYPE").get(null);
                if (rk != null) {
                    reg = invoke(ra, "registryOrThrow", rk);
                    if (reg == null) reg = unwrapOptional(invoke(ra, "registry", rk));
                }
            }
        } catch (Throwable ignored) {
        }
        dcRegCache = reg;
        dcRegProbed = true;
        regSourceLabel = reg == null ? "RegistryAccess 未取到（仅静态兜底）" : reg.getClass().getName();
        return reg;
    }

    private static Map<String, Object> index() {
        Map<String, Object> idx = typeIndex;
        if (idx != null) return idx;
        synchronized (DataComponents.class) {
            if (typeIndex != null) return typeIndex;
            Map<String, Object> built = new HashMap<>();
            indexInto(built, dataComponentRegistry());
            raIndexCount = built.size();
            indexInto(built, staticDcRegistry());
            staticIndexCount = built.size() - raIndexCount;
            int before = built.size();
            for (String id : KNOWN_STATIC_TYPES.keySet()) {
                Object direct = knownStaticType(id);
                if (direct != null) built.putIfAbsent(id.toLowerCase(Locale.ROOT), direct);
            }
            directIndexCount = built.size() - before;
            typeIndex = Map.copyOf(built);
            try {
                org.bukkit.plugin.java.JavaPlugin.getPlugin(ESLinkPlugin.class).getLogger()
                        .info("数据组件索引 " + built.size() + " 项（全量 " + raIndexCount
                                + " / 静态 " + staticIndexCount + " / 直取 " + directIndexCount
                                + "）· create:package_contents "
                                + (built.containsKey("create:package_contents") ? "在索引中" : "不在索引中"));
            } catch (Throwable ignored) {
            }
            return typeIndex;
        }
    }

    /** 枚举注册表：entrySet → registryKeySet → keySet 三级回退，最后才遍历值。 */
    private static void indexInto(Map<String, Object> idx, Object reg) {
        if (reg == null) return;
        if (indexByEntries(idx, reg)) return;
        if (indexByResourceKeys(idx, reg)) return;
        if (indexByResourceLocations(idx, reg)) return;
        if (!(reg instanceof Iterable<?> it)) return;
        for (Object type : it) {
            if (type == null) continue;
            String name = nameOf(type);
            if (usable(name)) idx.putIfAbsent(name.toLowerCase(Locale.ROOT), type);
        }
    }

    /** 静态注册表（兜底）。 */
    private static Object staticDcRegistry() {
        try {
            return Class.forName("net.minecraft.core.registries.BuiltInRegistries")
                    .getField("DATA_COMPONENT_TYPE").get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** entrySet 反查：NeoForge 上 MappedRegistry 的 key→value 最可靠（与 ItemKeys 同款）。 */
    private static boolean indexByEntries(Map<String, Object> idx, Object reg) {
        if (reg == null) return false;
        Object entries;
        try {
            entries = Reflect.method(reg.getClass(), "entrySet").invoke(reg);
        } catch (Throwable t) {
            return false;
        }
        if (!(entries instanceof Set<?> set)) return false;
        int added = 0;
        for (Object o : set) {
            if (!(o instanceof Map.Entry<?, ?> e)) continue;
            Object type = unwrapHolder(e.getValue());
            if (type == null) continue;
            String key = keyString(e.getKey());
            if (!usable(key)) continue;
            idx.putIfAbsent(key.toLowerCase(Locale.ROOT), type);
            added++;
        }
        return added > 0;
    }

    /** entrySet 拿不到时用 registryKeySet + get(ResourceKey)。 */
    private static boolean indexByResourceKeys(Map<String, Object> idx, Object reg) {
        Object keys = call(reg, "registryKeySet");
        if (!(keys instanceof Set<?> set) || set.isEmpty()) return false;
        int added = 0;
        for (Object rk : set) {
            String key = keyString(rk);
            if (!usable(key)) continue;
            Object type = lookupByResourceKey(reg, rk);
            if (type == null) continue;
            idx.putIfAbsent(key.toLowerCase(Locale.ROOT), type);
            added++;
        }
        return added > 0;
    }

    /** 再退到 keySet（ResourceLocation）+ get(ResourceLocation)。 */
    private static boolean indexByResourceLocations(Map<String, Object> idx, Object reg) {
        Object keys = call(reg, "keySet");
        if (!(keys instanceof Set<?> set) || set.isEmpty()) return false;
        int added = 0;
        for (Object rl : set) {
            String key = keyString(rl);
            if (!usable(key)) continue;
            Object type = lookupByLocation(reg, rl);
            if (type == null) continue;
            idx.putIfAbsent(key.toLowerCase(Locale.ROOT), type);
            added++;
        }
        return added > 0;
    }

    /** ResourceKey / ResourceLocation / String → 可用的注册名。 */
    private static String keyString(Object key) {
        if (key == null) return "";
        Object rl = key;
        if (!(rl instanceof String)) {
            Object loc = call(rl, "location");
            if (loc != null) rl = loc;
        }
        String s = String.valueOf(rl);
        return usable(s) ? s : "";
    }

    /** DataComponentType 的注册名：注册表反查 getKey/getResourceKey，避免依赖不可靠的 toString。 */
    private static String nameOf(Object type) {
        if (type == null) return "";
        String known = STATIC_IDS.get(type);
        if (usable(known)) return known;
        for (Object reg : new Object[]{dataComponentRegistry(), staticDcRegistry()}) {
            if (reg == null) continue;
            Object rl = invoke(reg, "getKey", type);
            if (rl == null) {
                Object ork = unwrapOptional(invoke(reg, "getResourceKey", type));
                if (ork != null) rl = call(ork, "location");
            }
            if (rl != null) {
                String s = String.valueOf(rl);
                if (usable(s)) return s;
            }
        }
        return String.valueOf(type);
    }

    /** 正查：先 ResourceLocation，再 ResourceKey，最后 Holder。 */
    private static Object lookup(String id) {
        Object rl = resourceLocation(id);
        if (rl == null) return null;
        for (Object reg : new Object[]{dataComponentRegistry(), staticDcRegistry()}) {
            if (reg == null) continue;
            Object r = lookupByLocation(reg, rl);
            if (r != null) return r;
            Object rk = resourceKey(reg, rl);
            if (rk != null) {
                r = lookupByResourceKey(reg, rk);
                if (r != null) return r;
            }
            Object holder = unwrapOptional(invoke(reg, "getHolder", rl));
            holder = unwrapHolder(holder);
            if (holder != null) return holder;
        }
        return null;
    }

    private static Object lookupByLocation(Object reg, Object rl) {
        Object r = unwrapOptional(invoke(reg, "get", rl));
        return r == null ? null : unwrapHolder(r);
    }

    private static Object lookupByResourceKey(Object reg, Object rk) {
        Object r = unwrapOptional(invoke(reg, "get", rk));
        return r == null ? null : unwrapHolder(r);
    }

    private static Object resourceKey(Object reg, Object rl) {
        if (reg == null || rl == null) return null;
        Object regKey = call(reg, "key");
        if (regKey == null) return null;
        try {
            Class<?> rk = Class.forName("net.minecraft.resources.ResourceKey");
            for (Method m : Reflect.methods(rk)) {
                if (!m.getName().equals("create") || m.getParameterCount() != 2) continue;
                if (!m.getParameterTypes()[0].isInstance(regKey)
                        || !m.getParameterTypes()[1].isInstance(rl)) continue;
                try {
                    Object made = m.invoke(null, regKey, rl);
                    if (made != null) return made;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
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

    /** 目标物品已经带着这个组件时，直接把它身上的 type 实例拿来写，绕开一切正查。 */
    private static Object typeFromItem(Object nms, String id) {
        if (nms == null || id == null) return null;
        for (Object[] e : entries(nms)) {
            String name = nameOf(e[0]);
            remember(e[0], name);
            if (id.equalsIgnoreCase(name)) return e[0];
        }
        return null;
    }

    private static void remember(Object type, String name) {
        if (type == null || !usable(name)) return;
        TYPES.putIfAbsent(name.toLowerCase(Locale.ROOT), type);
    }

    /**
     * Create 类的 public static 字段是最终兜底。字段值与注册表里的 type 是同一个单例，
     * 拿到它以后写入 create:package_contents 就完全不需要再按名正查。
     */
    private static Object knownStaticType(String id) {
        String key = id == null ? "" : id.toLowerCase(Locale.ROOT);
        String[] loc = KNOWN_STATIC_TYPES.get(key);
        if (loc == null) return null;
        Class<?> cls = classForName(loc[0]);
        if (cls == null) return null;
        try {
            Object value = cls.getField(loc[1]).get(null);
            if (value == null) return null;
            STATIC_IDS.put(value, key);
            TYPES.put(key, value);
            DIRECT_SOURCES.add("create:" + loc[1]);
            return value;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 自检预喂：直接拿 Create 的 PackageItem.containing() 造一个带内容的包裹，
     * 再从其组件表把真实 DataComponentType 实例收集进 TYPES。
     * 万一 AllDataComponents 字段因类加载器隔离取不到，这条路也能把索引喂饱。
     */
    public static int captureCreateTypes() {
        int found = 0;
        for (String id : KNOWN_STATIC_TYPES.keySet()) {
            if (knownStaticType(id) != null) found++;
        }
        templateTypeCount = capturePackageTemplate();
        return found + templateTypeCount;
    }

    private static int capturePackageTemplate() {
        String packageKey = "create:package_contents";
        Object cached = TYPES.get(packageKey);
        if (cached != null && cached != ABSENT) {
            templateLabel = "已有缓存，跳过模板";
            return 0;
        }
        try {
            Class<?> packageItem = classForName("com.simibubi.create.content.logistics.box.PackageItem");
            if (packageItem == null) {
                templateLabel = "Create 未安装或类不可见";
                return 0;
            }
            List<Object> probe = new ArrayList<>();
            for (String mat : new String[]{"minecraft:stone", "minecraft:oak_planks"}) {
                ItemStack st = ItemKeys.create(mat, 1);
                if (!ItemKeys.real(st)) continue;
                Object nms = ItemKeys.nmsOf(st);
                if (nms != null) probe.add(nms);
            }
            if (probe.isEmpty()) {
                templateLabel = "模板物品造不出";
                return 0;
            }
            Method containing = Reflect.method(packageItem, "containing", List.class);
            Object template = containing.invoke(null, probe);
            if (template == null) {
                templateLabel = "PackageItem.containing 返回 null";
                return 0;
            }
            try {
                Class<?> nmsStack = Class.forName("net.minecraft.world.item.ItemStack");
                Method addAddress = Reflect.method(packageItem, "addAddress", nmsStack, String.class);
                addAddress.invoke(null, template, "ESLink-probe");
            } catch (Throwable ignored) {
            }
            List<String> got = new ArrayList<>();
            for (Object[] e : entries(template)) {
                String name = nameOf(e[0]);
                if (!usable(name)) name = inferCreateComponent(e);
                if (!usable(name)) continue;
                TYPES.putIfAbsent(name.toLowerCase(Locale.ROOT), e[0]);
                STATIC_IDS.put(e[0], name);
                got.add(name);
            }
            templateLabel = got.isEmpty() ? "模板组件表为空" : "提取 " + String.join(",", got);
            return got.size();
        } catch (Throwable t) {
            templateLabel = "失败 " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage());
            return 0;
        }
    }

    /** 注册表拿不到名字时，按模板里出现顺序和值类型猜 Create 的关键组件。 */
    private static String inferCreateComponent(Object[] entry) {
        if (entry == null || entry.length < 2) return "";
        Object value = entry[1];
        if (value == null) return "";
        String cls = value.getClass().getName();
        if (cls.equals("net.minecraft.world.item.component.ItemContainerContents"))
            return "create:package_contents";
        if (cls.equals("com.simibubi.create.content.equipment.clipboard.ClipboardContent"))
            return "create:clipboard_content";
        if (value instanceof String) return "create:package_address";
        return "";
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

    private static Object unwrapOptional(Object r) {
        return r instanceof Optional<?> o ? o.orElse(null) : r;
    }

    private static boolean usable(String id) {
        return id != null && id.indexOf(':') > 0;
    }

    private static Class<?> classForName(String name) {
        List<ClassLoader> loaders = new ArrayList<>();
        ClassLoader own = DataComponents.class.getClassLoader();
        if (own != null) loaders.add(own);
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        if (ctx != null && ctx != own) loaders.add(ctx);
        ClassLoader sys = ClassLoader.getSystemClassLoader();
        if (sys != null && sys != own && sys != ctx) loaders.add(sys);
        for (ClassLoader loader : loaders) {
            try {
                return Class.forName(name, true, loader);
            } catch (Throwable ignored) {
            }
        }
        return null;
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
