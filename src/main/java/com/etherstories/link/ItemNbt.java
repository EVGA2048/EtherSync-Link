package com.etherstories.link;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 容器 / 模组物品用 NMS 来回。
 * Paper serializeAsBytes 在 Arclight 上会丢掉 create:package_contents。
 * 整包 codec 再失败就拆内含重装（ESN3）。
 */
public final class ItemNbt {

    private static final byte[] MAGIC0 = {'E', 'S', 'N', '0'};
    private static final byte[] MAGIC1 = {'E', 'S', 'N', '1'};
    private static final byte[] MAGIC2 = {'E', 'S', 'N', '2'};
    private static final byte[] MAGIC3 = {'E', 'S', 'N', '3'};
    private static final byte[] MAGIC4 = {'E', 'S', 'N', '4'};
    private static final byte[] MAGIC5 = {'E', 'S', 'N', '5'};
    private static final byte[] MAGIC6 = {'E', 'S', 'N', '6'};
    private static final Set<String> PACK_LOG = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final java.util.Map<String, Long> FORMAT_COUNTS = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile String lastSnapshotError = "";

    private static void count(String fmt) {
        FORMAT_COUNTS.merge(fmt, 1L, Long::sum);
    }

    /** 供 /link diag 展示的诊断行。 */
    public static List<String> diagLines() {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder("快照格式");
        List<String> keys = new ArrayList<>(FORMAT_COUNTS.keySet());
        java.util.Collections.sort(keys);
        for (String k : keys) sb.append(' ').append(k).append('=').append(FORMAT_COUNTS.get(k));
        out.add(sb.toString());
        if (lastSnapshotError != null && !lastSnapshotError.isBlank())
            out.add("最近快照失败 " + lastSnapshotError);
        return out;
    }

    private ItemNbt() {}

    public static byte[] save(ItemStack item) {
        if (!ItemKeys.real(item)) return null;
        int d = DEPTH.get();
        if (d > 6) return saveKeyOnly(item);
        DEPTH.set(d + 1);
        try {
            String key = ItemKeys.id(item);
            if (NestedItems.containerLike(key)) {
                byte[] packed = savePacked(item);
                if (packed != null && packed.length > 0) {
                    if (PACK_LOG.add("packed:" + key))
                        warn("容器改走安全拆装，不调用整包 NMS: " + key);
                    return packed;
                }
                // 读不到内含（流体包裹、模组背包）：没有可拆的子物品，整包发反而不丢东西
                if (PACK_LOG.add("whole:" + key))
                    warn("容器内含不可拆，改走整包快照: " + key + " · " + NestedItems.componentSummary(item));
            }
            StringBuilder err = new StringBuilder();
            byte[] nms = saveNms(item, err);
            if (nms != null && nms.length > 0) return namedWrap(nms, item);
            // 退到只存注册名 = 物品身上的数据全丢。以前这里不吭声，
            // 于是剪切板这类东西照发，落地却是空白的，没人知道发生了什么。
            if (PACK_LOG.add("keyonly:" + key))
                warn("整包快照失败，只能发注册名，物品数据会丢: " + key + " · " + err
                        + " · " + NestedItems.componentSummary(item));
            lastSnapshotError = key + " · " + err;
            return saveKeyOnly(item);
        } catch (Throwable t) {
            return null;
        } finally {
            DEPTH.set(d);
        }
    }

    public static ItemStack load(byte[] blob) {
        if (blob == null) return null;
        try {
            if (has(blob, MAGIC6)) return loadPacked5(payload(blob, MAGIC6), false, true).container();
            if (has(blob, MAGIC5)) return loadPacked5(payload(blob, MAGIC5), false, false).container();
            if (has(blob, MAGIC4)) return loadNamed(payload(blob, MAGIC4));
            if (has(blob, MAGIC3)) return loadPacked(payload(blob, MAGIC3));
            if (has(blob, MAGIC2)) {
                Object nms = loadStream(payload(blob, MAGIC2));
                return nms == null ? null : namedFromNms(nms);
            }
            if (has(blob, MAGIC1)) {
                Object tag = readTag(payload(blob, MAGIC1));
                if (tag == null) return null;
                Object nms = parseStack(tag, registryAccess());
                return nms == null ? null : namedFromNms(nms);
            }
            if (has(blob, MAGIC0)) return loadKeyOnly(payload(blob, MAGIC0));
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static boolean ours(byte[] blob) {
        return has(blob, MAGIC6) || has(blob, MAGIC5) || has(blob, MAGIC4) || has(blob, MAGIC3)
                || has(blob, MAGIC2) || has(blob, MAGIC1) || has(blob, MAGIC0);
    }

    public static String kind(byte[] blob) {
        if (blob == null || blob.length == 0) return "empty";
        if (has(blob, MAGIC6)) return "ESN6";
        if (has(blob, MAGIC5)) return "ESN5";
        if (has(blob, MAGIC4)) return "ESN4";
        if (has(blob, MAGIC3)) return "ESN3";
        if (has(blob, MAGIC2)) return "ESN2";
        if (has(blob, MAGIC1)) return "ESN1";
        if (has(blob, MAGIC0)) return "ESN0";
        return "raw/" + blob.length;
    }

    /** 带真实物品数据的快照。ESN0 只有注册名，还原不出来也没什么可丢的。 */
    public static boolean rich(byte[] blob) {
        return ours(blob) && !has(blob, MAGIC0);
    }

    public static boolean packed(byte[] blob) {
        return has(blob, MAGIC6) || has(blob, MAGIC5) || has(blob, MAGIC3);
    }

    public record PackedChild(String key, String name, int amount, byte[] blob, String nestedKeys) {}

    public record PackedSplit(ItemStack container, List<PackedChild> rejected) {}

    public record PackedDecoded(String key, int amount, String name, String address,
                                List<ItemStack> accepted, List<PackedChild> rejected) {}

    public static PackedSplit splitPacked(byte[] blob) {
        try {
            PackedDecoded decoded = decodePacked(blob, true);
            if (decoded == null) return null;
            ItemStack box = buildPacked(decoded);
            return ItemKeys.real(box) ? new PackedSplit(box, decoded.rejected()) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static PackedDecoded decodePacked(byte[] blob, boolean split) {
        if (!has(blob, MAGIC6) && !has(blob, MAGIC5)) return null;
        try {
            boolean v6 = has(blob, MAGIC6);
            return decodePacked5(payload(blob, v6 ? MAGIC6 : MAGIC5), split, v6);
        } catch (Throwable t) {
            return null;
        }
    }

    public static ItemStack buildPacked(PackedDecoded decoded) {
        if (decoded == null) return null;
        try {
            ItemStack box = ItemKeys.create(decoded.key(), decoded.amount());
            if (!ItemKeys.real(box)) return null;
            box = NestedItems.fill(box, decoded.accepted());
            if (!ItemKeys.real(box)) return null;
            box = NestedItems.applyPackageAddress(box, decoded.address());
            if (!decoded.accepted().isEmpty()) {
                List<ItemStack> check = NestedItems.inners(box);
                if (check == null || check.isEmpty()) return null;
            }
            if (!decoded.name().isBlank()) applyName(box, decoded.name());
            box.setAmount(Math.max(1, decoded.amount()));
            return box;
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] saveNms(ItemStack item, StringBuilder err) {
        try {
            Object nms = ItemKeys.nmsOf(item);
            if (nms == null) {
                err.append("[nms=null]");
                return null;
            }
            Object regs = registryAccess();
            // 带模组数据组件的物品优先走 NBT/codec：STREAM_CODEC 在部分混合端
            // 上可能不带模组组件，导致 create:package_contents 之类内容被静默丢弃。
            if (DataComponents.modded(item)) {
                Object tag = saveTag(nms, regs, err);
                if (tag == null) tag = saveCodecTag(nms, regs, err);
                if (tag != null) {
                    byte[] raw = writeTag(tag);
                    if (raw != null && raw.length > 0) {
                        count("ESN1-m");
                        return prefix(MAGIC1, raw);
                    }
                    err.append("[writeTag]");
                }
                byte[] stream = saveStream(nms, err);
                if (stream != null && stream.length > 0) {
                    count("ESN2-m");
                    return prefix(MAGIC2, stream);
                }
                return null;
            }
            byte[] stream = saveStream(nms, err);
            if (stream != null && stream.length > 0) {
                count("ESN2");
                return prefix(MAGIC2, stream);
            }
            Object tag = saveTag(nms, regs, err);
            if (tag == null) tag = saveCodecTag(nms, regs, err);
            if (tag == null) return null;
            byte[] raw = writeTag(tag);
            if (raw == null || raw.length == 0) {
                err.append("[writeTag]");
                return null;
            }
            count("ESN1");
            return prefix(MAGIC1, raw);
        } catch (Throwable t) {
            err.append("[saveNms ").append(t.getClass().getSimpleName()).append(']');
            return null;
        }
    }

    private static byte[] savePacked(ItemStack item) {
        String key = ItemKeys.id(item);
        if (!NestedItems.containerLike(key)) return null;
        // 读得出内含不代表装得回去：Create 纸箱的 package_contents 是它自己的组件类型，
        // 我们重建不了。这种拆了就还原不回来，只能整包发。
        if (!ContainerSupport.splittable(key)) return null;
        // 创造模式 Ctrl 复制出来的箱子/木桶，内容藏在 block_entity_data 里，
        // 按内含读会读成空的，拆包发过去就成了空箱子。这种一律整包。
        if (NestedItems.opaqueContents(item)) return null;
        List<ItemStack> inners = NestedItems.inners(item);
        if (inners == null) return null;
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(raw);
            out.writeUTF(nz(ItemKeys.id(item)));
            out.writeInt(Math.max(1, item.getAmount()));
            out.writeUTF(nz(plainName(item)));
            out.writeUTF(nz(NestedItems.packageAddress(item)));
            out.writeInt(inners.size());
            for (ItemStack in : inners) {
                byte[] child = ItemEnvelope.encode(in);
                if (child == null || child.length == 0) child = saveKeyOnly(in);
                if (child == null) return null;
                out.writeUTF(nz(ItemKeys.id(in)));
                out.writeUTF(nz(ItemCodec.display(in)));
                out.writeInt(Math.max(1, in.getAmount()));
                out.writeUTF(nz(NestedItems.csv(in)));
                out.writeInt(child.length);
                out.write(child);
            }
            out.flush();
            count("ESN6");
            return prefix(MAGIC6, raw.toByteArray());
        } catch (Throwable t) {
            return null;
        }
    }

    private static PackedSplit loadPacked5(byte[] raw, boolean split, boolean hasAddress) throws Exception {
        PackedDecoded decoded = decodePacked5(raw, split, hasAddress);
        if (decoded == null) return null;
        ItemStack box = buildPacked(decoded);
        return ItemKeys.real(box) ? new PackedSplit(box, decoded.rejected()) : null;
    }

    private static PackedDecoded decodePacked5(byte[] raw, boolean split, boolean hasAddress) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        String key = in.readUTF();
        int amount = in.readInt();
        String name = in.readUTF();
        String address = hasAddress ? in.readUTF() : "";
        int n = in.readInt();
        if (n < 0 || n > 81) return null;
        List<ItemStack> accepted = new ArrayList<>();
        List<PackedChild> rejected = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String childKey = in.readUTF();
            String childName = in.readUTF();
            int childAmount = in.readInt();
            String nested = in.readUTF();
            int len = in.readInt();
            if (len < 0 || len > 1_000_000) return null;
            byte[] child = in.readNBytes(len);
            if (child.length != len) return null;
            ItemStack it = ItemCodec.tryDecode(child, childKey, childAmount, nested);
            if (ItemKeys.real(it)) {
                accepted.add(it);
            } else if (split) {
                rejected.add(new PackedChild(childKey, childName, childAmount, child, nested));
            } else {
                return null;
            }
        }
        return new PackedDecoded(key, amount, name, address,
                List.copyOf(accepted), List.copyOf(rejected));
    }

    public static List<byte[]> packedChildren(byte[] blob) {
        boolean v6 = has(blob, MAGIC6);
        if (!v6 && !has(blob, MAGIC5)) return List.of();
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload(blob, v6 ? MAGIC6 : MAGIC5)));
            in.readUTF();
            in.readInt();
            in.readUTF();
            if (v6) in.readUTF();
            int n = in.readInt();
            if (n < 0 || n > 81) return List.of();
            List<byte[]> out = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                in.readUTF();
                in.readUTF();
                in.readInt();
                in.readUTF();
                int len = in.readInt();
                if (len <= 0 || len > 1_000_000) return List.of();
                byte[] child = in.readNBytes(len);
                if (child.length != len) return List.of();
                out.add(child);
            }
            return out;
        } catch (Throwable t) {
            return List.of();
        }
    }

    private static ItemStack loadPacked(byte[] raw) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        String key = in.readUTF();
        int amount = in.readInt();
        String name = in.readUTF();
        int n = in.readInt();
        if (n < 0 || n > 81) return null;
        List<ItemStack> inners = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int len = in.readInt();
            if (len < 0 || len > 1_000_000) return null;
            byte[] child = in.readNBytes(len);
            ItemStack it = load(child);
            if (!ItemKeys.real(it) && !ItemNbt.ours(child)) {
                try {
                    it = ItemStack.deserializeBytes(child);
                } catch (Throwable ignored) {
                }
            }
            if (!ItemKeys.real(it)) return null;
            inners.add(it);
        }
        ItemStack box = ItemKeys.create(key, amount);
        if (!ItemKeys.real(box)) return null;
        box = NestedItems.fill(box, inners);
        if (!ItemKeys.real(box)) return null;
        if (!name.isBlank()) applyName(box, name);
        box.setAmount(Math.max(1, amount));
        return box;
    }

    private static byte[] saveKeyOnly(ItemStack item) {
        if (!ItemKeys.real(item)) return null;
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(raw);
            out.writeUTF(nz(ItemKeys.id(item)));
            out.writeInt(Math.max(1, item.getAmount()));
            out.writeUTF(nz(plainName(item)));
            out.flush();
            count("ESN0");
            return prefix(MAGIC0, raw.toByteArray());
        } catch (Throwable t) {
            return null;
        }
    }

    private static ItemStack loadKeyOnly(byte[] raw) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        String key = in.readUTF();
        int amount = in.readInt();
        String name = in.available() > 0 ? in.readUTF() : "";
        ItemStack st = ItemKeys.create(key, amount);
        if (!ItemKeys.real(st)) return null;
        st.setAmount(Math.max(1, amount));
        applyName(st, name);
        return st;
    }

    private static String plainName(ItemStack item) {
        try {
            if (item.hasItemMeta() && item.getItemMeta().hasDisplayName())
                return item.getItemMeta().getDisplayName();
        } catch (Throwable ignored) {
        }
        return "";
    }

    static void applyName(ItemStack item, String name) {
        if (item == null || name == null || name.isBlank()) return;
        try {
            if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) return;
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return;
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        } catch (Throwable ignored) {
        }
    }

    private static byte[] namedWrap(byte[] inner, ItemStack item) {
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(raw);
            out.writeUTF(nz(plainName(item)));
            out.write(inner);
            out.flush();
            return prefix(MAGIC4, raw.toByteArray());
        } catch (Throwable t) {
            return inner;
        }
    }

    private static ItemStack loadNamed(byte[] raw) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        String name = in.readUTF();
        byte[] inner = in.readAllBytes();
        ItemStack st = load(inner);
        applyName(st, name);
        return st;
    }

    private static ItemStack namedFromNms(Object nms) throws Exception {
        ItemStack st = ItemKeys.fromNms(nms);
        applyName(st, NestedItems.legacyCustomName(nms));
        return st;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static void warn(String msg) {
        try {
            JavaPlugin.getPlugin(ESLinkPlugin.class).getLogger().warning(msg);
        } catch (Throwable ignored) {
        }
    }

    private static byte[] prefix(byte[] magic, byte[] raw) {
        byte[] out = new byte[magic.length + raw.length];
        System.arraycopy(magic, 0, out, 0, magic.length);
        System.arraycopy(raw, 0, out, magic.length, raw.length);
        return out;
    }

    private static byte[] payload(byte[] blob, byte[] magic) {
        byte[] raw = new byte[blob.length - magic.length];
        System.arraycopy(blob, magic.length, raw, 0, raw.length);
        return raw;
    }

    private static boolean has(byte[] blob, byte[] magic) {
        if (blob == null || blob.length < magic.length + 2) return false;
        for (int i = 0; i < magic.length; i++) if (blob[i] != magic[i]) return false;
        return true;
    }

    private static volatile Object registryAccessCache;

    /** 每件物品都要用，别每次重新 Class.forName 一遍服务端。 */
    static Object registryAccess() {
        Object cached = registryAccessCache;
        if (cached != null) return cached;
        Object found = findRegistryAccess();
        if (found != null) registryAccessCache = found;
        return found;
    }

    private static Object findRegistryAccess() {
        Class<?> ra = cls("net.minecraft.core.RegistryAccess");
        Object found = null;
        try {
            Class<?> ms = Class.forName("net.minecraft.server.MinecraftServer");
            Object server = Reflect.method(ms, "getServer").invoke(null);
            if (server != null) found = Reflect.method(server.getClass(), "registryAccess").invoke(server);
        } catch (Throwable ignored) {
        }
        if (isRegs(found, ra)) return found;
        try {
            Object bukkit = Bukkit.getServer();
            Object nms = Reflect.method(bukkit.getClass(), "getServer").invoke(bukkit);
            Class<?> ms = Class.forName("net.minecraft.server.MinecraftServer");
            if (nms != null && ms.isInstance(nms))
                found = Reflect.method(nms.getClass(), "registryAccess").invoke(nms);
        } catch (Throwable ignored) {
        }
        if (isRegs(found, ra)) return found;
        try {
            for (var w : Bukkit.getWorlds()) {
                Object handle = Reflect.method(w.getClass(), "getHandle").invoke(w);
                found = Reflect.method(handle.getClass(), "registryAccess").invoke(handle);
                if (isRegs(found, ra)) return found;
            }
        } catch (Throwable ignored) {
        }
        return unwrapRegs(found, ra);
    }

    private static boolean isRegs(Object o, Class<?> ra) {
        return o != null && (ra == null || ra.isInstance(o));
    }

    private static Object unwrapRegs(Object o, Class<?> ra) {
        if (o == null || ra == null) return o;
        if (ra.isInstance(o)) return o;
        for (Method m : Reflect.methods(o.getClass())) {
            if (m.getParameterCount() != 0 || !ra.isAssignableFrom(m.getReturnType())) continue;
            try {
                Object r = m.invoke(o);
                if (r != null) return r;
            } catch (Throwable ignored) {
            }
        }
        return o;
    }

    private static Class<?> cls(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] saveStream(Object nms, StringBuilder err) {
        try {
            Object codec = streamCodec();
            Object buf = newRegistryBuf();
            if (codec == null) {
                err.append("[no STREAM_CODEC]");
                return null;
            }
            if (buf == null) {
                err.append("[no RegistryBuf regs=").append(regType()).append(']');
                return null;
            }
            invokeEncode(codec, buf, nms);
            byte[] data = bytesOfBuf(buf);
            if (data == null || data.length == 0) {
                err.append("[stream empty]");
                return null;
            }
            return data;
        } catch (Throwable t) {
            err.append("[stream ").append(t.getClass().getSimpleName());
            if (t.getMessage() != null) err.append(':').append(shortMsg(t));
            err.append(']');
            return null;
        }
    }

    private static String regType() {
        Object r = registryAccess();
        return r == null ? "null" : r.getClass().getName();
    }

    private static String shortMsg(Throwable t) {
        Throwable c = t.getCause() != null ? t.getCause() : t;
        String m = c.getMessage();
        if (m == null) return c.getClass().getSimpleName();
        return m.length() > 80 ? m.substring(0, 80) : m;
    }

    private static Object loadStream(byte[] raw) {
        try {
            Object codec = streamCodec();
            Object buf = wrapRegistryBuf(raw);
            if (codec == null || buf == null) return null;
            return invokeDecode(codec, buf);
        } catch (Throwable t) {
            return null;
        }
    }

    private static volatile Object streamCodecCache;
    private static volatile boolean streamCodecProbed;

    private static Object streamCodec() {
        if (streamCodecProbed) return streamCodecCache;
        Class<?> stack;
        try {
            stack = Class.forName("net.minecraft.world.item.ItemStack");
        } catch (Throwable t) {
            streamCodecProbed = true;
            return null;
        }
        for (String f : new String[]{"STREAM_CODEC", "OPTIONAL_STREAM_CODEC"}) {
            try {
                Object c = stack.getField(f).get(null);
                if (c != null) {
                    streamCodecCache = c;
                    break;
                }
            } catch (Throwable ignored) {
            }
        }
        streamCodecProbed = true;
        return streamCodecCache;
    }

    private static Object newRegistryBuf() throws Exception {
        Object netty = Reflect.method(Class.forName("io.netty.buffer.Unpooled"), "buffer").invoke(null);
        return registryBuf(netty);
    }

    private static Object wrapRegistryBuf(byte[] raw) throws Exception {
        Object netty = Reflect.method(Class.forName("io.netty.buffer.Unpooled"), "wrappedBuffer", byte[].class)
                .invoke(null, raw);
        return registryBuf(netty);
    }

    private static Object registryBuf(Object netty) throws Exception {
        Object regs = registryAccess();
        Class<?> rf = Class.forName("net.minecraft.network.RegistryFriendlyByteBuf");
        for (Constructor<?> c : rf.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 2 && p[0].isInstance(netty) && regs != null && p[1].isInstance(regs))
                return c.newInstance(netty, regs);
            if (p.length == 3 && p[0].isInstance(netty) && regs != null && p[1].isInstance(regs)) {
                Object third = firstEnum(p[2]);
                if (third != null) return c.newInstance(netty, regs, third);
            }
        }
        return null;
    }

    private static Object firstEnum(Class<?> type) {
        try {
            if (type.isEnum()) {
                Object[] c = type.getEnumConstants();
                return c != null && c.length > 0 ? c[0] : null;
            }
            for (Method m : Reflect.methods(type)) {
                if (m.getParameterCount() == 0 && type.isAssignableFrom(m.getReturnType())
                        && (m.getName().startsWith("get") || m.getName().equals("play"))) {
                    try {
                        Object r = m.invoke(null);
                        if (r != null) return r;
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static byte[] bytesOfBuf(Object buf) throws Exception {
        Object unwrapped = null;
        try {
            unwrapped = Reflect.method(buf.getClass(), "unwrap").invoke(buf);
        } catch (Throwable ignored) {
        }
        for (Object b : new Object[]{buf, unwrapped}) {
            if (b == null) continue;
            try {
                int n = (int) Reflect.method(b.getClass(), "readableBytes").invoke(b);
                if (n <= 0) continue;
                byte[] data = new byte[n];
                Reflect.method(b.getClass(), "readBytes", byte[].class).invoke(b, data);
                return data;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void invokeEncode(Object codec, Object buf, Object nms) throws Exception {
        try {
            Class<?> sc = Class.forName("net.minecraft.network.codec.StreamCodec");
            for (Method m : Reflect.methods(sc)) {
                if (!m.getName().equals("encode") || m.getParameterCount() != 2) continue;
                m.invoke(codec, buf, nms);
                return;
            }
        } catch (Throwable ignored) {
        }
        for (Method m : Reflect.methods(codec.getClass())) {
            if (!m.getName().equals("encode") || m.getParameterCount() != 2) continue;
            if (!m.getParameterTypes()[1].isInstance(nms)
                    && !m.getParameterTypes()[1].isAssignableFrom(nms.getClass())) continue;
            m.invoke(codec, buf, nms);
            return;
        }
        throw new IllegalStateException("no encode");
    }

    private static Object invokeDecode(Object codec, Object buf) throws Exception {
        try {
            Class<?> sc = Class.forName("net.minecraft.network.codec.StreamCodec");
            for (Method m : Reflect.methods(sc)) {
                if (!m.getName().equals("decode") || m.getParameterCount() != 1) continue;
                Object r = m.invoke(codec, buf);
                if (r instanceof Optional<?> o) r = o.orElse(null);
                if (r != null) return r;
            }
        } catch (Throwable ignored) {
        }
        for (Method m : Reflect.methods(codec.getClass())) {
            if (!m.getName().equals("decode") || m.getParameterCount() != 1) continue;
            Object r = m.invoke(codec, buf);
            if (r instanceof Optional<?> o) r = o.orElse(null);
            if (r != null) return r;
        }
        return null;
    }

    private static Object saveTag(Object nms, Object regs, StringBuilder err) {
        try {
            Class<?> compound = Class.forName("net.minecraft.nbt.CompoundTag");
            for (Method m : Reflect.methods(nms.getClass())) {
                if (!m.getName().equals("save") && !m.getName().equals("saveOptional")) continue;
                Class<?>[] p = m.getParameterTypes();
                try {
                    Object r = null;
                    if (p.length == 1 && regs != null && p[0].isInstance(regs))
                        r = m.invoke(nms, regs);
                    else if (p.length == 2 && regs != null && p[0].isInstance(regs) && p[1] == compound)
                        r = m.invoke(nms, regs, compound.getConstructor().newInstance());
                    else if (p.length == 1 && p[0] == compound)
                        r = m.invoke(nms, compound.getConstructor().newInstance());
                    else if (p.length == 0)
                        r = m.invoke(nms);
                    if (r instanceof Optional<?> o) r = o.orElse(null);
                    if (r != null) return r;
                } catch (Throwable t) {
                    err.append("[save ").append(shortMsg(t)).append(']');
                }
            }
        } catch (Throwable t) {
            err.append("[saveTag]");
        }
        return null;
    }

    private static Object saveCodecTag(Object nms, Object regs, StringBuilder err) {
        try {
            Object codec = Class.forName("net.minecraft.world.item.ItemStack").getField("CODEC").get(null);
            Object ops = Class.forName("net.minecraft.nbt.NbtOps").getField("INSTANCE").get(null);
            Object use = ops;
            if (regs != null) {
                try {
                    Class<?> dyn = Class.forName("com.mojang.serialization.DynamicOps");
                    use = Reflect.method(regs.getClass(), "createSerializationContext", dyn).invoke(regs, ops);
                } catch (Throwable ignored) {
                }
            }
            for (Method m : Reflect.methods(codec.getClass())) {
                if (!m.getName().equals("encodeStart") || m.getParameterCount() != 2) continue;
                Object r = m.invoke(codec, use, nms);
                Object tag = unwrapDataResult(r);
                if (tag != null) return tag;
            }
        } catch (Throwable t) {
            err.append("[codec ").append(shortMsg(t)).append(']');
        }
        return null;
    }

    private static Object unwrapDataResult(Object r) {
        if (r == null) return null;
        try {
            Object opt = Reflect.method(r.getClass(), "result").invoke(r);
            if (opt instanceof Optional<?> o) return o.orElse(null);
        } catch (Throwable ignored) {
        }
        try {
            return Reflect.method(r.getClass(), "getOrThrow").invoke(r);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object parseStack(Object tag, Object regs) throws Exception {
        Class<?> stack = Class.forName("net.minecraft.world.item.ItemStack");
        for (String name : new String[]{"parseOptional", "parse", "of"}) {
            for (Method m : Reflect.methods(stack)) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] p = m.getParameterTypes();
                try {
                    Object r = null;
                    if (p.length == 2 && regs != null && p[0].isInstance(regs) && p[1].isInstance(tag))
                        r = m.invoke(null, regs, tag);
                    else if (p.length == 1 && p[0].isInstance(tag))
                        r = m.invoke(null, tag);
                    if (r instanceof Optional<?> o) r = o.orElse(null);
                    if (r != null) return r;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static byte[] writeTag(Object tag) throws Exception {
        Class<?> nbtIo = Class.forName("net.minecraft.nbt.NbtIo");
        Class<?> compound = Class.forName("net.minecraft.nbt.CompoundTag");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            Reflect.method(nbtIo, "writeCompressed", compound, OutputStream.class).invoke(null, tag, out);
            return out.toByteArray();
        } catch (Throwable ignored) {
        }
        out.reset();
        try {
            Class<?> tagCls = Class.forName("net.minecraft.nbt.Tag");
            Reflect.method(nbtIo, "writeCompressed", tagCls, OutputStream.class).invoke(null, tag, out);
            return out.toByteArray();
        } catch (Throwable ignored) {
        }
        out.reset();
        DataOutputStream dos = new DataOutputStream(out);
        Reflect.method(nbtIo, "write", compound, DataOutput.class).invoke(null, tag, dos);
        dos.flush();
        return out.toByteArray();
    }

    private static Object readTag(byte[] raw) throws Exception {
        Class<?> nbtIo = Class.forName("net.minecraft.nbt.NbtIo");
        ByteArrayInputStream in = new ByteArrayInputStream(raw);
        try {
            return Reflect.method(nbtIo, "readCompressed", InputStream.class).invoke(null, in);
        } catch (Throwable ignored) {
        }
        in.reset();
        try {
            Class<?> acc = Class.forName("net.minecraft.nbt.NbtAccounter");
            Object unlimited = Reflect.method(acc, "unlimitedHeap").invoke(null);
            return Reflect.method(nbtIo, "readCompressed", InputStream.class, acc).invoke(null, in, unlimited);
        } catch (Throwable ignored) {
        }
        in.reset();
        return Reflect.method(nbtIo, "read", java.io.DataInput.class).invoke(null, new java.io.DataInputStream(in));
    }
}
