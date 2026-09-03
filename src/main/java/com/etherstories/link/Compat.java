package com.etherstories.link;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 各服启动时把自己的物品注册名清单发布到库里，别的服拉下来缓存。
 * 发货前先查一遍对面有没有这个物品，没有就别收走，省掉"发出去→拒收→退回"那一圈。
 *
 * 注意这只是前置过滤，不是保证：ID 两边都有也可能因为重建方式不同而失败，
 * 所以退回机制还得留着。
 */
public final class Compat {
    private static final Map<String, Set<String>> PEERS = new ConcurrentHashMap<>();
    private static final Map<String, String> DIGESTS = new ConcurrentHashMap<>();
    private static volatile String selfDigest;
    private static volatile int selfCount;

    private Compat() {}

    public static int knownFor(String code) {
        Set<String> s = PEERS.get(key(code));
        return s == null ? -1 : s.size();
    }

    public static boolean haveList(String code) {
        return PEERS.containsKey(key(code));
    }

    /** 对面明确没有这个物品才返回 true。清单没拉到就一律放行，不能因为没数据就卡住发货。 */
    public static boolean missing(String code, String itemKey) {
        if (itemKey == null || itemKey.isBlank()) return false;
        Set<String> have = PEERS.get(key(code));
        if (have == null || have.isEmpty()) return false;
        return !have.contains(itemKey.toLowerCase(Locale.ROOT));
    }

    /** 返回对面缺的第一个键（含容器内含），都齐了返回 null。 */
    public static String firstMissing(String code, String itemKey, String nestedCsv) {
        if (missing(code, itemKey)) return itemKey;
        for (String k : NestedItems.split(nestedCsv)) {
            if ("opaque".equalsIgnoreCase(k)) continue;
            if (missing(code, k)) return k;
        }
        return null;
    }

    public static void publish(ESLinkPlugin plugin) {
        try {
            Collection<String> keys = ItemKeys.allKeys();
            if (keys.isEmpty()) return;
            int count = keys.size();
            if (selfDigest != null && count == selfCount) return;
            byte[] packed = pack(keys);
            String digest = sha1(packed);
            selfCount = count;
            if (digest.equals(selfDigest)) return;
            plugin.store().publishRegistry(plugin.serverCode(), digest, keys.size(), packed);
            selfDigest = digest;
            plugin.getLogger().info("已发布本服物品清单 " + keys.size() + " 项 · " + packed.length / 1024 + "KB");
        } catch (Exception e) {
            plugin.getLogger().warning("发布物品清单失败: " + e.getMessage());
        }
    }

    /** 只有 digest 变了才拉正文。 */
    public static void refresh(ESLinkPlugin plugin) {
        try {
            Map<String, String> digests = plugin.store().registryDigests();
            String self = plugin.serverCode();
            for (Map.Entry<String, String> e : digests.entrySet()) {
                String code = key(e.getKey());
                if (code.equalsIgnoreCase(self)) continue;
                if (e.getValue() != null && e.getValue().equals(DIGESTS.get(code))) continue;
                Store.RegistryRow row = plugin.store().registryOf(e.getKey());
                if (row == null || row.payload() == null) continue;
                Set<String> keys = unpack(row.payload());
                if (keys.isEmpty()) continue;
                PEERS.put(code, keys);
                DIGESTS.put(code, row.digest());
                plugin.getLogger().info("已同步 " + e.getKey() + " 的物品清单 " + keys.size() + " 项");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("同步物品清单失败: " + e.getMessage());
        }
    }

    public static java.util.List<String> lines(ESLinkPlugin plugin) {
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add("本服 " + plugin.serverCode() + " 清单 " + (selfCount > 0 ? selfCount + " 项" : "未发布"));
        if (PEERS.isEmpty()) {
            out.add("对端清单 无（发货前置检查不生效，一律放行）");
            return out;
        }
        for (Map.Entry<String, Set<String>> e : PEERS.entrySet()) {
            out.add("对端 " + e.getKey() + " 清单 " + e.getValue().size() + " 项");
        }
        return out;
    }

    private static byte[] pack(Collection<String> keys) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(raw)) {
            StringBuilder sb = new StringBuilder();
            for (String k : keys) {
                if (k == null || k.isBlank()) continue;
                sb.append(k.toLowerCase(Locale.ROOT)).append('\n');
            }
            gz.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        return raw.toByteArray();
    }

    private static Set<String> unpack(byte[] payload) {
        Set<String> out = new HashSet<>();
        try (GZIPInputStream gz = new GZIPInputStream(new java.io.ByteArrayInputStream(payload));
             java.io.BufferedReader r = new java.io.BufferedReader(
                     new java.io.InputStreamReader(gz, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (!t.isEmpty()) out.add(t);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String sha1(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(data);
        StringBuilder sb = new StringBuilder(40);
        for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }

    private static String key(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }
}
