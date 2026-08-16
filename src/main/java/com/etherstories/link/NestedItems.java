package com.etherstories.link;

import org.bukkit.block.BlockState;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 潜影盒 / 背包等嵌套物品：TX 先列出里面所有注册名，RX 对不上就不要 deserialize。
 * 对不上变成屏障，不要把有毒 NBT 放进箱子再等人打开炸服。
 */
public final class NestedItems {

    /** 正则从 NBT 里刮出来的候选名大多是垃圾，逐个查注册表要封顶。 */
    private static final int BLOB_PROBE_CAP = 96;
    private static final Pattern ID = Pattern.compile("(?:[a-z0-9_]{2,32}):(?:[a-z0-9_./-]{2,128})");
    private static final Set<String> SKIP_NS = Set.of(
            "http", "https", "www", "text", "extra", "color", "bold", "italic",
            "click", "hover", "nbt", "type", "slot", "count", "tag", "name", "id");

    private NestedItems() {}

    public static String csv(ItemStack item) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        walk(item, keys, 0);
        String root = ItemKeys.id(item);
        if (ItemKeys.usable(root)) keys.add(root);
        ExtraKeys.addTo(item, keys);
        boolean walked = walkedInv(item) || createContents(item) != null;
        if (containerLike(root) && !walked) {
            int probed = 0;
            for (String k : fromBlob(bytesOf(item))) {
                if (++probed > BLOB_PROBE_CAP) break;
                if (ItemKeys.create(k, 1) != null) keys.add(k);
            }
            if (onlyRoot(keys, root)) return join(keys) + (keys.isEmpty() ? "opaque" : ",opaque");
        }
        return join(keys);
    }

    /** 空容器没有内含要重建，自检过不过都不该拦它。opaque 表示读不到内含，不算空。 */
    /** 内容存在 block_entity_data 里：按内含读不全，拆了必丢东西，只能整包发。 */
    public static boolean opaqueContents(ItemStack item) {
        return DataComponents.has(item, "minecraft:block_entity_data");
    }

    public static boolean emptyContents(String itemKey, String nestedCsv) {
        if (!containerLike(itemKey)) return false;
        if (nestedCsv == null || nestedCsv.isBlank()) return true;
        for (String raw : nestedCsv.split(",")) {
            String k = raw.trim();
            if (k.isEmpty()) continue;
            if (!k.equalsIgnoreCase(itemKey)) return false;
        }
        return true;
    }

    public static boolean emptyBox(ItemStack it) {
        String key = ItemKeys.id(it);
        if (!containerLike(key)) return false;
        List<ItemStack> in = inners(it);
        return in != null && in.isEmpty();
    }

    public static boolean safeToDecode(String itemKey, String nestedCsv, byte[] blob) {
        if (opaque(nestedCsv) && !ItemNbt.ours(blob)) return false;
        List<String> keys = split(nestedCsv);
        if (keys.isEmpty() && containerLike(itemKey) && (blob == null || blob.length == 0)) return false;
        if (keys.isEmpty() && ItemKeys.usable(itemKey)) keys.add(itemKey);
        for (String k : keys) {
            if ("opaque".equalsIgnoreCase(k) || ExtraKeys.tagged(k) || !ItemKeys.usable(k)) continue;
            if (knownHere(k, itemKey, blob)) continue;
            return false;
        }
        if (ItemKeys.usable(itemKey) && !knownHere(itemKey, itemKey, blob)) return false;
        return true;
    }

    private static boolean knownHere(String key, String root, byte[] blob) {
        if (ItemKeys.present(key)) return true;
        if (fluidPresent(key)) return true;
        boolean hasBlob = blob != null && blob.length > 0;
        if (hasBlob && (key.equalsIgnoreCase(root) || isCreatePackage(key))) return true;
        return false;
    }

    public static boolean hasBadInner(ItemStack item) {
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof BlockStateMeta bsm
                    && bsm.getBlockState() instanceof InventoryHolder h) {
                for (ItemStack it : h.getInventory().getContents()) {
                    if (it != null && !it.getType().isAir() && !ItemKeys.real(it)) return true;
                }
            }
            if (meta instanceof BundleMeta b) {
                for (ItemStack it : b.getItems()) {
                    if (it != null && !ItemKeys.real(it)) return true;
                }
            }
            List<ItemStack> pack = createContents(item);
            if (pack != null) {
                for (ItemStack it : pack) {
                    if (it != null && !it.getType().isAir() && !ItemKeys.real(it)) return true;
                }
            }
        } catch (Throwable t) {
            return true;
        }
        return false;
    }

    public static boolean skipBlob(String itemKey, String nestedCsv) {
        return skipBlob(itemKey, nestedCsv, null);
    }

    public static boolean skipBlob(String itemKey, String nestedCsv, byte[] blob) {
        if (ItemNbt.ours(blob)) return false;
        if (opaque(nestedCsv)) return true;
        if (containerLike(itemKey) && (nestedCsv == null || nestedCsv.isBlank())) return true;
        return false;
    }

    /** 声明了内含，还原后却是空的：不要当成功。 */
    public static boolean emptiedCreate(ItemStack st, String itemKey, String csv) {
        return emptiedContainer(st, itemKey, csv);
    }

    public static boolean emptiedContainer(ItemStack st, String itemKey, String csv) {
        if (st == null || !hasDeclaredInners(csv, itemKey)) return false;
        if (!containerLike(itemKey) && !containerLike(ItemKeys.id(st))) return false;
        // 内含组件还挂在物品上就说明东西没丢，只是本服解析不了
        // （Create 纸箱的 package_contents 是它自己的组件类型，整包快照能原样还原，我们读不懂）。
        // 拿"读不出来"当"丢了"，就会把完好的包裹原样退回去。
        if (carriesContents(st)) return false;
        List<ItemStack> inner = inners(st);
        return inner != null && inner.isEmpty();
    }

    private static final String[] CONTENT_COMPONENTS = {
            "create:package_contents",
            "minecraft:container",
            "minecraft:bundle_contents",
            "minecraft:block_entity_data",
    };

    private static boolean carriesContents(ItemStack st) {
        for (String id : CONTENT_COMPONENTS) {
            if (DataComponents.has(st, id)) return true;
        }
        return false;
    }

    /** 能看见内含则返回列表（可空）；看不了返回 null。 */
    public static List<ItemStack> inners(ItemStack item) {
        if (item == null) return null;
        List<ItemStack> out = new ArrayList<>();
        boolean saw = false;
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof BlockStateMeta bsm
                    && bsm.getBlockState() instanceof InventoryHolder h) {
                saw = true;
                for (ItemStack it : h.getInventory().getContents()) {
                    if (ItemKeys.real(it)) out.add(it);
                }
            }
            if (meta instanceof BundleMeta b) {
                saw = true;
                for (ItemStack it : b.getItems()) {
                    if (ItemKeys.real(it)) out.add(it);
                }
            }
        } catch (Throwable ignored) {
        }
        if (saw) return out;
        List<ItemStack> pack = createContents(item);
        if (pack != null) return new ArrayList<>(pack);
        List<ItemStack> vanilla = componentItems(item, "minecraft:container");
        return vanilla == null ? null : new ArrayList<>(vanilla);
    }

    /** 按内含重装：潜影盒走 Bukkit，Create 纸箱写 package_contents。 */
    public static ItemStack fill(ItemStack box, List<ItemStack> inners) {
        if (box == null) return null;
        if (inners == null) inners = List.of();
        ItemStack out = box.clone();
        // 没内含就别写空的容器组件，否则箱子看着是空的却带着组件，跟普通箱子堆叠不了。
        if (inners.isEmpty()) return out;
        if (looksLikePackage(ItemKeys.id(out))
                && (usesCreatePackageContents(ItemKeys.id(out))
                || hasComponent(out, "create:package_contents"))) {
            ItemStack n = applyComponent(out, "create:package_contents", inners);
            return n != null ? n : out;
        }
        if (applyBukkitInv(out, inners)) return out;
        ItemStack v = applyComponent(out, "minecraft:container", inners);
        return v != null ? v : out;
    }

    private static boolean applyBukkitInv(ItemStack box, List<ItemStack> inners) {
        try {
            ItemMeta meta = box.getItemMeta();
            if (meta instanceof BlockStateMeta bsm
                    && bsm.getBlockState() instanceof InventoryHolder h) {
                var inv = h.getInventory();
                inv.clear();
                int i = 0;
                for (ItemStack it : inners) {
                    if (i >= inv.getSize()) break;
                    if (ItemKeys.real(it)) inv.setItem(i, it);
                    i++;
                }
                bsm.setBlockState((BlockState) h);
                box.setItemMeta(bsm);
                return true;
            }
            if (meta instanceof BundleMeta b) {
                List<ItemStack> items = new ArrayList<>();
                for (ItemStack it : inners) if (ItemKeys.real(it)) items.add(it);
                b.setItems(items);
                box.setItemMeta(b);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static ItemStack applyComponent(ItemStack box, String componentId, List<ItemStack> inners) {
        try {
            Object contents = makeContainer(inners);
            return contents == null ? null : DataComponents.write(box, componentId, contents);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object makeContainer(List<ItemStack> inners) throws Exception {
        List<Object> nms = new ArrayList<>();
        for (ItemStack it : inners) {
            if (!ItemKeys.real(it)) continue;
            Object s = ItemKeys.nmsOf(it);
            if (s != null) nms.add(s);
        }
        Class<?> icc = Class.forName("net.minecraft.world.item.component.ItemContainerContents");
        for (String name : new String[]{"fromItems", "of"}) {
            try {
                return Reflect.method(icc, name, List.class).invoke(null, nms);
            } catch (Throwable ignored) {
            }
        }
        for (Method m : Reflect.methods(icc)) {
            if ((m.getModifiers() & java.lang.reflect.Modifier.STATIC) == 0) continue;
            if (m.getParameterCount() != 1) continue;
            if (!List.class.isAssignableFrom(m.getParameterTypes()[0])) continue;
            try {
                return m.invoke(null, nms);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean hasDeclaredInners(String csv, String root) {
        for (String k : split(csv)) {
            if ("opaque".equalsIgnoreCase(k) || ExtraKeys.tagged(k) || !ItemKeys.usable(k)) continue;
            if (root != null && k.equalsIgnoreCase(root)) continue;
            if (isCreatePackage(k)) continue;
            return true;
        }
        return false;
    }

    public static String firstMissing(String nestedCsv, String itemKey) {
        List<String> keys = split(nestedCsv);
        if (ItemKeys.usable(itemKey) && !containsIgnore(keys, itemKey)) keys.add(0, itemKey);
        for (String k : keys) {
            if ("opaque".equalsIgnoreCase(k) || ExtraKeys.tagged(k) || !ItemKeys.usable(k)) continue;
            if (!ItemKeys.present(k) && ItemKeys.create(k, 1) == null && !fluidPresent(k)) return k;
        }
        if (ExtraKeys.refuse()) {
            String extra = ExtraKeys.firstUnknown(nestedCsv);
            if (extra != null) return extra;
        }
        return itemKey;
    }

    public static boolean opaque(String csv) {
        if (csv == null || csv.isBlank()) return false;
        for (String p : csv.split(",")) {
            if ("opaque".equalsIgnoreCase(p.trim())) return true;
        }
        return false;
    }

    public static boolean containerLike(String id) {
        if (id == null) return false;
        String s = id.toLowerCase(Locale.ROOT);
        return s.contains("shulker") || s.contains("bundle") || s.contains("backpack")
                || s.contains("satchel") || s.contains("pouch") || s.contains("toolbox")
                || s.contains("suitcase") || s.contains("quiver")
                || s.contains("travelersbackpack") || s.contains("sophisticated")
                || s.contains("drawer") || s.contains("crate") || s.contains("locker")
                || looksLikePackage(s);
    }

    /** Create 6 纸箱：create:cardboard_package_12x12 / rare_xxx_package。不是 packager。 */
    static boolean isCreatePackage(String id) {
        if (id == null) return false;
        String s = id.toLowerCase(Locale.ROOT);
        if (s.contains("packager") || s.contains("frogport") || s.contains("package_filter")) return false;
        return s.startsWith("create:")
                && (s.contains("cardboard_package") || s.endsWith("_package") || s.endsWith(":package"));
    }

    static boolean packageLike(String id) {
        return looksLikePackage(id);
    }

    private static boolean looksLikePackage(String id) {
        if (id == null) return false;
        String s = id.toLowerCase(Locale.ROOT);
        if (s.contains("packager") || s.contains("frogport") || s.contains("package_filter")) return false;
        return s.contains("cardboard_package") || s.endsWith("_package") || s.endsWith(":package");
    }

    private static boolean usesCreatePackageContents(String id) {
        return isCreatePackage(id);
    }

    /** 流体包裹装的是流体不是物品，不能按内含拆，整包发过去就行。 */
    static boolean fluidPackage(String id) {
        if (id == null) return false;
        String s = id.toLowerCase(Locale.ROOT);
        return s.contains("fluid_package") || s.contains("fluid_box");
    }

    static boolean createPackage(String id) {
        return isCreatePackage(id);
    }

    static List<String> split(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        for (String p : csv.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String join(Set<String> keys) {
        if (keys == null || keys.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            if (k == null || k.isBlank() || "opaque".equalsIgnoreCase(k)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(k);
        }
        return sb.toString();
    }

    private static boolean onlyRoot(Set<String> keys, String root) {
        int n = 0;
        for (String k : keys) {
            if (ExtraKeys.tagged(k) || !ItemKeys.usable(k)) continue;
            if (root != null && k.equalsIgnoreCase(root)) continue;
            n++;
        }
        return n == 0;
    }

    private static boolean containsIgnore(List<String> keys, String id) {
        for (String k : keys) {
            if (k.equalsIgnoreCase(id)) return true;
        }
        return false;
    }

    private static void walk(ItemStack item, Set<String> out, int depth) {
        if (item == null || depth > 6 || !ItemKeys.real(item)) return;
        String id = ItemKeys.id(item);
        if (ItemKeys.usable(id)) out.add(id);
        try {
            walkMeta(item, out, depth);
        } catch (Throwable ignored) {
        }
        try {
            List<ItemStack> pack = createContents(item);
            if (pack != null) {
                for (ItemStack it : pack) walk(it, out, depth + 1);
            }
        } catch (Throwable ignored) {
        }
        try {
            List<ItemStack> vanilla = componentItems(item, "minecraft:container");
            if (vanilla != null) {
                for (ItemStack it : vanilla) walk(it, out, depth + 1);
            }
        } catch (Throwable ignored) {
        }
        if (!containerLike(id)) {
            try {
                walkObj(item.serialize(), out, depth + 1);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void walkMeta(ItemStack item, Set<String> out, int depth) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        if (meta instanceof BlockStateMeta bsm) {
            BlockState st = bsm.getBlockState();
            if (st instanceof InventoryHolder holder) {
                for (ItemStack it : holder.getInventory().getContents()) walk(it, out, depth + 1);
            }
        }
        if (meta instanceof BundleMeta bundle) {
            for (ItemStack it : bundle.getItems()) walk(it, out, depth + 1);
        }
        if (meta instanceof CrossbowMeta bow) {
            for (ItemStack it : bow.getChargedProjectiles()) walk(it, out, depth + 1);
        }
    }

    private static boolean walkedInv(ItemStack item) {
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof BlockStateMeta bsm
                    && bsm.getBlockState() instanceof InventoryHolder) return true;
            if (meta instanceof BundleMeta) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static void walkObj(Object o, Set<String> out, int depth) {
        if (o == null || depth > 8) return;
        if (o instanceof ItemStack st) {
            walk(st, out, depth);
            return;
        }
        if (o instanceof Map<?, ?> map) {
            Object type = map.get("type");
            if (type == null) type = map.get("id");
            if (type instanceof String s) addType(s, out);
            if (map.containsKey("==") || map.containsKey("v") || map.containsKey("type")) {
                try {
                    ItemStack st = ItemStack.deserialize((Map<String, Object>) map);
                    walk(st, out, depth + 1);
                    return;
                } catch (Throwable ignored) {
                }
            }
            for (Object v : map.values()) walkObj(v, out, depth + 1);
            return;
        }
        if (o instanceof Iterable<?> it) {
            for (Object v : it) walkObj(v, out, depth + 1);
        }
    }

    private static void addType(String raw, Set<String> out) {
        if (raw == null || raw.isBlank()) return;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (!s.contains(":")) s = "minecraft:" + s;
        if (ItemKeys.usable(s)) out.add(s);
    }

    static String legacyCustomName(Object nms) {
        try {
            Object type = DataComponents.type("minecraft:custom_name");
            if (nms == null || type == null) return "";
            Object name = invokeGet(nms, type);
            if (name == null) return "";
            try {
                for (String cls : new String[]{
                        "org.bukkit.craftbukkit.util.CraftChatMessage",
                        "org.bukkit.craftbukkit.v1_21_R1.util.CraftChatMessage",
                        "org.bukkit.craftbukkit.v.util.CraftChatMessage"
                }) {
                    try {
                        Object s = Reflect.method(Class.forName(cls), "fromComponent", name.getClass()).invoke(null, name);
                        if (s instanceof String str && !str.isBlank()) return str;
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
            Object s = Reflect.method(name.getClass(), "getString").invoke(name);
            return s == null ? "" : s.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** Create 6 纸箱：读 create:package_contents。空箱返回空列表；读不到返回 null。 */
    static List<ItemStack> createContents(ItemStack box) {
        if (box == null) return null;
        String id = ItemKeys.id(box);
        if (!looksLikePackage(id) || fluidPackage(id)) return null;
        if (!usesCreatePackageContents(id) && !hasComponent(box, "create:package_contents"))
            return null;
        List<ItemStack> direct = createApiContents(box);
        if (direct != null && !direct.isEmpty()) return direct;
        List<ItemStack> comp = componentItems(box, "create:package_contents");
        return comp != null ? comp : direct;
    }

    private static List<ItemStack> createApiContents(ItemStack box) {
        try {
            Object nms = ItemKeys.nmsOf(box);
            if (nms == null) return null;
            Method get = createGetContents(nms);
            if (get == null) return null;
            Object handler = get.invoke(null, nms);
            if (handler == null) return null;
            Method slotsMethod = Reflect.find(handler.getClass(), "getSlots", 0);
            Method stackMethod = Reflect.find(handler.getClass(), "getStackInSlot", 1);
            if (slotsMethod == null || stackMethod == null) return null;
            int slots = ((Number) slotsMethod.invoke(handler)).intValue();
            List<ItemStack> out = new ArrayList<>();
            for (int i = 0; i < slots && i < 81; i++) {
                Object child = stackMethod.invoke(handler, i);
                addStack(child, out);
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static volatile Method createGetContents;
    private static volatile boolean createProbed;

    /** Create 的物品类带渲染方法，解析一次就够，别每个包裹都去碰它。 */
    private static Method createGetContents(Object nms) {
        if (createProbed) return createGetContents;
        synchronized (NestedItems.class) {
            if (createProbed) return createGetContents;
            Method found = null;
            try {
                Class<?> type = Class.forName("com.simibubi.create.content.logistics.box.PackageItem");
                for (Method m : Reflect.methods(type)) {
                    if (!m.getName().equals("getContents") || m.getParameterCount() != 1) continue;
                    if (!m.getParameterTypes()[0].isInstance(nms)) continue;
                    found = m;
                    break;
                }
            } catch (Throwable ignored) {
            }
            createGetContents = found;
            createProbed = true;
            return found;
        }
    }

    static String packageAddress(ItemStack box) {
        if (box == null || !usesCreatePackageContents(ItemKeys.id(box))) return "";
        Object value = DataComponents.read(box, "create:package_address");
        return value instanceof String s ? s : "";
    }

    static ItemStack applyPackageAddress(ItemStack box, String address) {
        if (box == null || !usesCreatePackageContents(ItemKeys.id(box))
                || address == null || address.isBlank()) return box;
        ItemStack out = DataComponents.write(box, "create:package_address", address);
        return ItemKeys.real(out) ? out : box;
    }

    static String componentSummary(ItemStack item) {
        List<String> names = DataComponents.names(item);
        return names.isEmpty() ? "components=?" : String.join(",", names);
    }

    private static boolean hasComponent(ItemStack box, String componentId) {
        return DataComponents.has(box, componentId);
    }

    static List<ItemStack> componentItems(ItemStack box, String componentId) {
        if (box == null || componentId == null) return null;
        try {
            Object contents = DataComponents.read(box, componentId);
            if (contents == null) return DataComponents.has(box, componentId) ? List.of() : null;
            List<ItemStack> out = stacksFromContainer(contents);
            return out == null ? List.of() : out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean fluidPresent(String id) {
        if (!ItemKeys.usable(id)) return false;
        try {
            Object rl = resourceLocation(id);
            Object reg = Class.forName("net.minecraft.core.registries.BuiltInRegistries")
                    .getField("FLUID").get(null);
            Object r = invoke1(reg, "getOptional", rl);
            return r instanceof java.util.Optional<?> o && o.isPresent();
        } catch (Throwable t) {
            return false;
        }
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

    private static Object invokeGet(Object nms, Object type) {
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

    private static List<ItemStack> stacksFromContainer(Object contents) {
        List<ItemStack> out = new ArrayList<>();
        try {
            Object stream = Reflect.method(contents.getClass(), "stream").invoke(contents);
            if (stream instanceof java.util.stream.Stream<?> s) {
                s.forEach(o -> addStack(o, out));
                return out;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object stream = Reflect.method(contents.getClass(), "nonEmptyStream").invoke(contents);
            if (stream instanceof java.util.stream.Stream<?> s) {
                s.forEach(o -> addStack(o, out));
                return out;
            }
        } catch (Throwable ignored) {
        }
        try {
            int slots = (int) Reflect.method(contents.getClass(), "getSlots").invoke(contents);
            for (int i = 0; i < slots && i < 27; i++) {
                Object st = null;
                try {
                    st = Reflect.method(contents.getClass(), "getStackInSlot", int.class).invoke(contents, i);
                } catch (Throwable ignored) {
                }
                if (st == null) {
                    try {
                        st = Reflect.method(contents.getClass(), "copyOne", int.class).invoke(contents, i);
                    } catch (Throwable ignored) {
                    }
                }
                addStack(st, out);
            }
            return out;
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void addStack(Object o, List<ItemStack> out) {
        if (o == null) return;
        try {
            if (o instanceof ItemStack st) {
                if (ItemKeys.real(st)) out.add(st);
                return;
            }
            ItemStack st = ItemKeys.fromNms(o);
            if (ItemKeys.real(st)) out.add(st);
        } catch (Throwable ignored) {
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

    private static byte[] bytesOf(ItemStack item) {
        try {
            return item.serializeAsBytes();
        } catch (Throwable t) {
            return new byte[0];
        }
    }

    private static List<String> fromBlob(byte[] blob) {
        List<String> out = new ArrayList<>();
        if (blob == null || blob.length == 0) return out;
        String s = new String(blob, StandardCharsets.UTF_8);
        Matcher m = ID.matcher(s);
        LinkedHashSet<String> uniq = new LinkedHashSet<>();
        while (m.find()) {
            String id = m.group();
            int i = id.indexOf(':');
            if (i < 2) continue;
            String ns = id.substring(0, i);
            String path = id.substring(i + 1);
            if (SKIP_NS.contains(ns)) continue;
            if (path.chars().noneMatch(Character::isLetter)) continue;
            uniq.add(id);
        }
        out.addAll(uniq);
        return out;
    }
}
