package com.etherstories.link;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 跨服普通物品信封：原生快照 + 可降级投影 + 一次性恢复编号。 */
public final class ItemEnvelope {

    private static final byte[] INITIAL = {'E', 'S', 'T', '1'};
    private static final byte[] RETURN = {'E', 'S', 'R', '1'};
    private static final ThreadLocal<Map<UUID, byte[]>> RESOLVED =
            ThreadLocal.withInitial(Map::of);

    public record Escrow(UUID token, String originServer, byte[] payload) {}

    private ItemEnvelope() {}

    static byte[] encode(ItemStack item) {
        if (!ItemKeys.real(item)) return null;
        if (NestedItems.containerLike(ItemKeys.id(item))) return ItemNbt.save(item);
        UUID proxy = ExtraKeys.token(item);
        if (proxy != null) return encodeReturn(item, proxy);
        byte[] nativeBlob = ItemNbt.save(item);
        if (nativeBlob == null || nativeBlob.length == 0) return null;
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(raw);
            out.writeUTF(UUID.randomUUID().toString());
            out.writeUTF(plugin().serverCode());
            out.writeUTF(ItemKeys.id(item));
            out.writeInt(Math.max(1, item.getAmount()));
            out.writeUTF(displayName(item));
            out.writeInt(damage(item));
            out.writeInt(nativeBlob.length);
            out.write(nativeBlob);
            out.flush();
            return prefix(INITIAL, raw.toByteArray());
        } catch (Throwable t) {
            return null;
        }
    }

    static ItemStack decode(byte[] blob, String nestedKeys) {
        try {
            if (has(blob, INITIAL)) return decodeInitial(blob, nestedKeys);
            if (has(blob, RETURN)) return decodeReturn(blob, nestedKeys);
        } catch (Throwable ignored) {
        }
        return null;
    }

    static boolean ours(byte[] blob) {
        return has(blob, INITIAL) || has(blob, RETURN);
    }

    static boolean returning(byte[] blob) {
        return has(blob, RETURN);
    }

    static void useResolved(Map<UUID, byte[]> payloads) {
        RESOLVED.set(payloads == null ? Map.of() : payloads);
    }

    static void clearResolved() {
        RESOLVED.remove();
    }

    static List<Escrow> escrows(byte[] blob) {
        List<Escrow> out = new ArrayList<>();
        collectEscrows(blob, out, 0);
        return out;
    }

    static List<UUID> returnTokens(byte[] blob) {
        List<UUID> out = new ArrayList<>();
        collectReturns(blob, out, 0);
        return out;
    }

    private static void collectEscrows(byte[] blob, List<Escrow> out, int depth) {
        if (blob == null || depth > 8) return;
        if (has(blob, INITIAL)) {
            try {
                Initial x = readInitial(blob);
                out.add(new Escrow(x.token(), x.originServer(), blob));
            } catch (Throwable ignored) {
            }
            return;
        }
        for (byte[] child : ItemNbt.packedChildren(blob))
            collectEscrows(child, out, depth + 1);
    }

    private static void collectReturns(byte[] blob, List<UUID> out, int depth) {
        if (blob == null || depth > 8) return;
        if (has(blob, RETURN)) {
            try {
                UUID token = readReturn(blob).token();
                if (!out.contains(token)) out.add(token);
            } catch (Throwable ignored) {
            }
            return;
        }
        for (byte[] child : ItemNbt.packedChildren(blob))
            collectReturns(child, out, depth + 1);
    }

    private static byte[] encodeReturn(ItemStack item, UUID token) {
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(raw);
            out.writeUTF(token.toString());
            out.writeInt(Math.max(1, item.getAmount()));
            out.writeInt(damage(item));
            out.flush();
            return prefix(RETURN, raw.toByteArray());
        } catch (Throwable t) {
            return null;
        }
    }

    private static ItemStack decodeInitial(byte[] blob, String nestedKeys) throws Exception {
        Initial x = readInitial(blob);
        ItemStack item = ItemNbt.load(x.nativeBlob());
        if (!ItemKeys.real(item) || !ItemKeys.same(ItemKeys.id(item), x.key())) {
            // 快照里明明有数据却还原不出来，这时候造个空物品交给玩家就是静默丢东西。
            // 宁可退回发送端，那边的原件是完好的。
            if (ItemNbt.rich(x.nativeBlob())) return null;
            item = ItemKeys.create(x.key(), x.amount());
        }
        if (!ItemKeys.real(item)) return null;
        applyProjection(item, x.amount(), x.name(), x.damage());
        return ExtraKeys.reconcile(item, blob, nestedKeys);
    }

    private static ItemStack decodeReturn(byte[] blob, String nestedKeys) throws Exception {
        Returning r = readReturn(blob);
        byte[] initial = RESOLVED.get().get(r.token());
        if (initial == null || !has(initial, INITIAL)) return null;
        Initial x = readInitial(initial);
        ItemStack item = ItemNbt.load(x.nativeBlob());
        if (!ItemKeys.real(item)) return null;
        applyProjection(item, r.amount(), x.name(), Math.max(x.damage(), r.damage()));
        ExtraKeys.clearProxy(item);
        return item;
    }

    private static Initial readInitial(byte[] blob) throws Exception {
        DataInputStream in = stream(blob, INITIAL);
        UUID token = UUID.fromString(in.readUTF());
        String origin = in.readUTF();
        String key = in.readUTF();
        int amount = in.readInt();
        String name = in.readUTF();
        int damage = in.readInt();
        int len = in.readInt();
        if (len <= 0 || len > 1_000_000) throw new IllegalArgumentException("bad native length");
        byte[] nativeBlob = in.readNBytes(len);
        if (nativeBlob.length != len) throw new IllegalArgumentException("short native blob");
        return new Initial(token, origin, key, amount, name, damage, nativeBlob);
    }

    private static Returning readReturn(byte[] blob) throws Exception {
        DataInputStream in = stream(blob, RETURN);
        return new Returning(UUID.fromString(in.readUTF()), in.readInt(), in.readInt());
    }

    private static void applyProjection(ItemStack item, int amount, String name, int damage) {
        item.setAmount(Math.max(1, Math.min(amount, Math.max(1, item.getMaxStackSize()))));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        boolean changed = false;
        if (name != null && !name.isBlank() && !meta.hasDisplayName()) {
            meta.setDisplayName(name);
            changed = true;
        }
        int max = item.getType().getMaxDurability();
        if (max > 0 && meta instanceof Damageable d) {
            int want = Math.max(0, Math.min(damage, max));
            if (d.getDamage() != want) {
                d.setDamage(want);
                changed = true;
            }
        }
        // setItemMeta 会把 Arclight 上模组自己注册的数据组件洗掉（剪切板内容就是这么没的）。
        // 名字和耐久都在快照里带过来了，绝大多数情况根本没东西要改，那就别碰它。
        if (changed) item.setItemMeta(meta);
    }

    private static int damage(ItemStack item) {
        try {
            ItemMeta meta = item.getItemMeta();
            return meta instanceof Damageable d ? Math.max(0, d.getDamage()) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String displayName(ItemStack item) {
        try {
            ItemMeta meta = item.getItemMeta();
            return meta != null && meta.hasDisplayName() ? meta.getDisplayName() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static DataInputStream stream(byte[] blob, byte[] magic) {
        return new DataInputStream(new ByteArrayInputStream(blob, magic.length, blob.length - magic.length));
    }

    private static byte[] prefix(byte[] magic, byte[] raw) {
        byte[] out = new byte[magic.length + raw.length];
        System.arraycopy(magic, 0, out, 0, magic.length);
        System.arraycopy(raw, 0, out, magic.length, raw.length);
        return out;
    }

    private static boolean has(byte[] blob, byte[] magic) {
        if (blob == null || blob.length <= magic.length) return false;
        for (int i = 0; i < magic.length; i++) if (blob[i] != magic[i]) return false;
        return true;
    }

    private static ESLinkPlugin plugin() {
        return org.bukkit.plugin.java.JavaPlugin.getPlugin(ESLinkPlugin.class);
    }

    private record Initial(UUID token, String originServer, String key, int amount,
                           String name, int damage, byte[] nativeBlob) {}

    private record Returning(UUID token, int amount, int damage) {}
}
