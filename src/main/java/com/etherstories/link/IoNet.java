package com.etherstories.link;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.block.Container;
import org.bukkit.block.data.AnaloguePowerable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 红石跨服（事件时间戳缓冲队列，当前默认）。
 * 电平变化写入 link_io_events，接收端按原间隔回放。
 * 状态轮询备份：同目录 IoNet.java.state_version。
 */
public final class IoNet {
    private static final Material RX_BODY = Material.TARGET;
    private static final Material TX_BODY = Material.REDSTONE_LAMP;
    private static final long SIGN_MIN_GAP_MS = 1000L;
    private final ESLinkPlugin plugin;
    private final AtomicBoolean polling = new AtomicBoolean();
    private final Map<Integer, Models.IoRow> local = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> lastSent = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastWriteAt = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> hold = new ConcurrentHashMap<>();
    private final Map<Integer, String> signSnap = new ConcurrentHashMap<>();
    private final Map<Integer, Long> signAt = new ConcurrentHashMap<>();
    private final Set<String> txKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> rxKeys = ConcurrentHashMap.newKeySet();
    private final Set<Integer> badBody = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Block> blockCache = new ConcurrentHashMap<>();
    
    // 事件回放所需的额外内存结构
    private final Map<Integer, Long> lastEventId = new ConcurrentHashMap<>();
    private final Map<Integer, ReplaySession> replaySessions = new ConcurrentHashMap<>();
    private volatile long cacheAtMs;
    private volatile long lastPruneMs;

    public static class ReplaySession {
        public final Queue<Models.IoEvent> queue = new java.util.concurrent.ConcurrentLinkedQueue<>();
        public long lastPlayedTimeMs = 0L;
        public long lastEventTimeMs = 0L;
        public boolean active = false;
    }

    public IoNet(ESLinkPlugin plugin) { this.plugin = plugin; }
    /** 每 tick 运行，驱动红石逻辑与事件回放 */
    public void pulse() {
        if (!plugin.store().ready() || local.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Models.IoRow n : local.values()) {
            Block b = block(n);
            boolean here = b != null && loaded(b);
            if ("TX".equals(n.role())) {
                pushTx(n, (paused(n) || !here) ? 0 : readPower(b), now, false);
            } else if ("RX".equals(n.role())) {
                if (!here) continue;
                boolean ok = !paused(n) && live(n);
                if (!ok) {
                    applyRx(n, b, 0);
                    ReplaySession s = replaySessions.get(n.id());
                    if (s != null) {
                        s.queue.clear();
                        s.active = false;
                    }
                    continue;
                }

                ReplaySession s = replaySessions.computeIfAbsent(n.id(), id -> new ReplaySession());
                
                // 1. 如果没有激活播放，且队列里有新事件，则初始化
                if (!s.active && !s.queue.isEmpty()) {
                    Models.IoEvent first = s.queue.poll();
                    applyRx(n, b, NodeSigns.mapLogic(n.logic(), true, first.level()));
                    s.lastPlayedTimeMs = now;
                    s.lastEventTimeMs = first.timeMs();
                    s.active = true;
                }

                // 2. 依次按相对时间间隔播放后续事件
                while (s.active && !s.queue.isEmpty()) {
                    Models.IoEvent next = s.queue.peek();
                    long eventDelta = next.timeMs() - s.lastEventTimeMs;
                    long playbackElapsed = now - s.lastPlayedTimeMs;

                    // 安全对策：如果队列由于服务器卡顿积压超过 50 个事件，进行快进，丢弃历史并以最新状态重置
                    if (s.queue.size() > 50) {
                        s.queue.clear();
                        applyRx(n, b, NodeSigns.mapLogic(n.logic(), true, next.level()));
                        s.active = false;
                        break;
                    }

                    if (playbackElapsed >= eventDelta) {
                        s.queue.poll();
                        applyRx(n, b, NodeSigns.mapLogic(n.logic(), true, next.level()));
                        s.lastPlayedTimeMs += eventDelta; // 保持节奏线对齐
                        s.lastEventTimeMs = next.timeMs();
                    } else {
                        break;
                    }
                }

                // 3. 播放完毕重置 active 状态，以便下一次新信号进入时可以即时响应
                if (s.queue.isEmpty()) {
                    s.active = false;
                }
            }
        }
    }

    public void poll() {
        if (!plugin.store().ready()) return;
        if (!polling.compareAndSet(false, true)) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var nodes = plugin.store().ioOn(plugin.serverCode());
                long now = System.currentTimeMillis();
                if (now - lastPruneMs > 30_000L) {
                    lastPruneMs = now;
                    try {
                        plugin.store().pruneIoEvents(15000L);
                    } catch (Exception ignored) {
                    }
                }

                Map<String, Long> warmAfter = new HashMap<>();
                Set<String> coldPairs = new HashSet<>();
                for (var n : nodes) {
                    if (!"RX".equals(n.role())) continue;
                    String pair = n.pairCode();
                    if (pair == null || pair.isBlank()) continue;
                    Long lastId = lastEventId.get(n.id());
                    if (lastId == null) coldPairs.add(pair);
                    else warmAfter.merge(pair, lastId, Math::min);
                }
                Map<String, Long> coldMax = new HashMap<>();
                for (String pair : coldPairs) {
                    try {
                        coldMax.put(pair, plugin.store().maxIoEventId(pair));
                    } catch (Exception ignored) {
                    }
                }
                Map<String, List<Models.IoEvent>> fresh = new HashMap<>();
                for (var e : warmAfter.entrySet()) {
                    try {
                        List<Models.IoEvent> ev = plugin.store().ioEventsAfter(e.getKey(), e.getValue());
                        if (!ev.isEmpty()) fresh.put(e.getKey(), ev);
                    } catch (Exception ignored) {
                    }
                }

                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        applyPoll(nodes, coldMax, fresh);
                    } finally {
                        polling.set(false);
                    }
                });
            } catch (Exception e) {
                polling.set(false);
                plugin.getLogger().warning("红石节点扫描失败: " + e.getMessage());
            }
        });
    }

    private void applyPoll(List<Models.IoRow> nodes, Map<String, Long> coldMax,
                           Map<String, List<Models.IoEvent>> fresh) {
        Set<Integer> seen = new HashSet<>();
        txKeys.clear();
        rxKeys.clear();
        cacheAtMs = System.currentTimeMillis();
        for (var n : nodes) {
            seen.add(n.id());
            local.put(n.id(), n);
            if ("TX".equals(n.role())) {
                txKeys.add(ESLinkPlugin.locKey(n.world(), n.x(), n.y(), n.z()));
            }
            if ("RX".equals(n.role())) {
                rxKeys.add(ESLinkPlugin.locKey(n.world(), n.x(), n.y(), n.z()));
                Block body = block(n);
                if (body != null && loaded(body)) {
                    ensureBody(n, body);
                    Integer want = hold.get(n.id());
                    if (want != null && readSignal(body) != want) hold.remove(n.id());
                }
                String pair = n.pairCode();
                if (pair != null && !pair.isBlank()) {
                    Long lastId = lastEventId.get(n.id());
                    if (lastId == null) {
                        Long maxId = coldMax.get(pair);
                        if (maxId != null) {
                            lastEventId.put(n.id(), maxId);
                            Block rx = block(n);
                            if (rx != null) {
                                applyRx(n, rx, NodeSigns.mapLogic(n.logic(), true, n.peerLevel()));
                            }
                        }
                    } else {
                        List<Models.IoEvent> events = fresh.get(pair);
                        if (events != null && !events.isEmpty()) {
                            ReplaySession s = replaySessions.computeIfAbsent(n.id(), id -> new ReplaySession());
                            long cursor = lastId;
                            for (Models.IoEvent ev : events) {
                                if (ev.id() <= lastId) continue;
                                s.queue.add(ev);
                                if (ev.id() > cursor) cursor = ev.id();
                            }
                            lastEventId.put(n.id(), cursor);
                        }
                    }
                }
            }
            String st = signStatus(n);
            String snap = n.unit() + "|" + n.peerUnit() + "|" + st;
            if (!snap.equals(signSnap.get(n.id()))) {
                refreshSign(n, false);
                signSnap.put(n.id(), snap);
            }
        }
        local.keySet().removeIf(id -> !seen.contains(id));
        blockCache.keySet().removeIf(id -> !seen.contains(id));
        lastEventId.keySet().removeIf(id -> !seen.contains(id));
    }

    public boolean isTx(String world, int x, int y, int z) {
        return txKeys.contains(ESLinkPlugin.locKey(world, x, y, z));
    }
    public boolean isRxNode(Block b) {
        return b != null && isRxAt(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }
    public boolean isRxAt(String world, int x, int y, int z) {
        return rxKeys.contains(ESLinkPlugin.locKey(world, x, y, z));
    }
    public boolean hasRxNodes() {
        return !rxKeys.isEmpty();
    }
    public boolean hasTxNodes() {
        return !txKeys.isEmpty();
    }
    public int heldAt(Block node) {
        if (node == null || !isRxNode(node)) return -1;
        for (Models.IoRow n : local.values()) {
            if (!"RX".equals(n.role())) continue;
            if (n.x() == node.getX() && n.y() == node.getY() && n.z() == node.getZ()
                    && n.world().equals(node.getWorld().getName())) {
                return hold.getOrDefault(n.id(), 0);
            }
        }
        return -1;
    }

    public void onPowerHint(Block b) {
        if (b == null || !isTx(b.getWorld().getName(), b.getX(), b.getY(), b.getZ())) return;
        Models.IoRow n = findTx(b);
        if (n == null) return;
        pushTx(n, loaded(b) ? readPower(b) : 0, System.currentTimeMillis(), false);
    }

    public void onChunkUnload(Chunk ch) {
        String world = ch.getWorld().getName();
        int cx = ch.getX(), cz = ch.getZ();
        long now = System.currentTimeMillis();
        for (Models.IoRow n : local.values()) {
            if (!n.world().equals(world) || (n.x() >> 4) != cx || (n.z() >> 4) != cz) continue;
            if ("TX".equals(n.role())) pushTx(n, 0, now, true);
            if ("RX".equals(n.role())) {
                hold.put(n.id(), 0);
                ReplaySession s = replaySessions.get(n.id());
                if (s != null) {
                    s.queue.clear();
                    s.active = false;
                }
            }
        }
    }

    public void paint() {
        for (Models.IoRow n : local.values()) {
            Block b = block(n);
            if (b == null || !loaded(b)) continue;
            String st = signStatus(n);
            String snap = n.unit() + "|" + n.peerUnit() + "|" + st;
            if (snap.equals(signSnap.get(n.id()))) continue;
            refreshSign(n, false);
            signSnap.put(n.id(), snap);
        }
    }

    public Models.IoRow cachedAt(String world, int x, int y, int z) {
        for (Models.IoRow n : local.values()) {
            if (n.x() == x && n.y() == y && n.z() == z && n.world().equals(world)) return n;
        }
        return null;
    }

    public void refreshSign(Models.IoRow n) {
        refreshSign(n, true);
    }

    public void refreshSign(Models.IoRow n, boolean placeIfMissing) {
        if (n == null) return;
        local.put(n.id(), n);
        Block b = block(n);
        if (b == null || !loaded(b)) return;
        Sign sign = ChestListener.findSign(b);
        if (sign == null && placeIfMissing) sign = ChestListener.ensureSign(b, null);
        if (sign == null) return;
        String other = (n.pairCode() == null || n.pairCode().isBlank()) ? "" : otherServer(n.pairCode());
        String via = NodeSigns.via(plugin, n.role(), n.pairCode(), other);
        NodeSigns.write(sign, "io", n.role(), n.unit(), n.peerUnit(), via, signStatus(n));
    }

    public void setup(Player p, String role) {
        if (!p.hasPermission("eslink.chest")) {
            plugin.msg(p, "&c没有权限");
            return;
        }
        if (!plugin.store().ready()) {
            plugin.msg(p, "&c数据库未连接");
            return;
        }
        Block node = ChestListener.sessionIo(plugin, p);
        if (node == null) {
            plugin.msg(p, "&c请对准红石灯（发送）或标靶（接收），再输入 /link io");
            return;
        }
        if (ChestListener.chestLike(node)) {
            plugin.msg(p, "&c箱子请用 /link chest");
            return;
        }
        Material want = bodyFor(role);
        if (node.getType() != want) {
            if (!isIoBody(node.getType())) {
                plugin.msg(p, "TX".equals(role)
                        ? "&c请对准红石灯再设为发送端"
                        : "&c请对准标靶再设为接收端");
                return;
            }
            node.setType(want, false);
            plugin.msg(p, "RX".equals(role) ? "&7已换成标靶：比较器读它就是 0–15。"
                    : "&7已换成红石灯：给它红石信号即可发送。");
        }
        ChestListener.clearLook(plugin, p);
        ChestListener.ensureSign(node, ChestListener.faceFromPlayer(p, node));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (plugin.store().banned(plugin.serverCode(), p.getUniqueId())) {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c你已被禁止放置互通节点"));
                    return;
                }
                Models.ChestRow chest = plugin.store().chestAt(plugin.serverCode(),
                        node.getWorld().getName(), node.getX(), node.getY(), node.getZ());
                if (chest != null) {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c该坐标已是运输箱 UNIT " + chest.unit()));
                    return;
                }
                Models.IoRow old = plugin.store().ioAt(plugin.serverCode(),
                        node.getWorld().getName(), node.getX(), node.getY(), node.getZ());
                if (old != null && !plugin.canManage(p, old.owner())) {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c这是别人的红石节点"));
                    return;
                }
                if (old != null) plugin.store().deleteIo(old.id());
                int id = plugin.store().insertIo(plugin.serverCode(), role,
                        node.getWorld().getName(), node.getX(), node.getY(), node.getZ(),
                        p.getUniqueId(), p.getName());
                Models.IoRow row = plugin.store().ioById(id);
                try {
                    plugin.store().setWatch(p.getUniqueId(), p.getName(), "io", id, true);
                } catch (Exception ignored) {
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if ("RX".equals(role)) {
                        hold.remove(id);
                        lastEventId.remove(id);
                        ReplaySession s = replaySessions.remove(id);
                        if (s != null) s.queue.clear();
                    }
                    if (row != null) {
                        local.put(row.id(), row);
                        refreshSign(row);
                    }
                    Sessions.State st = plugin.sessions().of(p);
                    st.pairKind = "io";
                    st.pairRole = role;
                    st.pendingChestId = id;
                    plugin.msg(p, "&a已登记红石 " + role + "  UNIT " + (row == null ? Units.code(id) : row.unit())
                            + "。" + ("RX".equals(role)
                            ? "接收端：贴一个比较器（背对木桶）读 0–15，相邻红石粉也会直接带电。"
                            : "发送端：拉杆、红石粉、比较器给它信号都行，强度原样过去。"));
                    plugin.gui().openPairServers(p);
                });
            } catch (Exception ex) {
                Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c登记失败: " + ex.getMessage()));
            }
        });
    }

    public void unlink(Player p) {
        if (!p.hasPermission("eslink.chest")) {
            plugin.msg(p, "&c没有权限");
            return;
        }
        Block node = ChestListener.sessionIo(plugin, p);
        if (node == null) {
            plugin.msg(p, "&c请对准红石控制器");
            return;
        }
        ChestListener.clearLook(plugin, p);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Models.IoRow row = plugin.store().ioAt(plugin.serverCode(),
                        node.getWorld().getName(), node.getX(), node.getY(), node.getZ());
                if (row == null) {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c这不是红石控制器"));
                    return;
                }
                if (!plugin.canManage(p, row.owner())) {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c这是别人的红石节点"));
                    return;
                }
                plugin.store().deleteIo(row.id());
                forget(row.id());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    clearOut(node);
                    Sign sign = ChestListener.findSign(node);
                    if (sign != null) sign.getBlock().setType(Material.AIR);
                    plugin.msg(p, "&7已拆除红石节点 UNIT " + row.unit());
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c取消失败"));
            }
        });
    }

    public void removeAt(Block node, Models.IoRow row) {
        forget(row.id());
        clearOut(node);
        Sign sign = ChestListener.findSign(node);
        if (sign != null) sign.getBlock().setType(Material.AIR);
    }

    private void pushTx(Models.IoRow n, int level, long now, boolean force) {
        int lv = clamp(level);
        Integer prev = lastSent.get(n.id());
        
        // 只有电平改变（或者第一次加载、强制更新）才产生写库事件
        if (prev != null && prev == lv && !force) {
            return;
        }
        
        lastSent.put(n.id(), lv);
        lastWriteAt.put(n.id(), now);
        signLater(n);
        
        if (n.pairCode() != null && !n.pairCode().isBlank()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    // 1. 同时更新 link_io 表以保证拉取基准线时的 peerLevel() 正确
                    plugin.store().writeIoLevel(n.id(), lv);
                    // 2. 插入具体的改变事件到 link_io_events 表
                    plugin.store().insertIoEvent(n.pairCode(), lv, now);
                } catch (Exception ignored) {
                }
            });
        }
    }

    private void applyRx(Models.IoRow n, Block node, int level) {
        int lv = clamp(level);
        if (hold.getOrDefault(n.id(), Integer.MIN_VALUE) == lv) return;
        hold.put(n.id(), lv);
        if (node == null || !loaded(node)) return;
        writeSignal(node, lv);
        notifyPower(node, lv);
        signLater(n);
    }

    private static void writeSignal(Block node, int level) {
        int lv = clamp(level);
        BlockData cur = node.getBlockData();
        if (cur instanceof AnaloguePowerable p) {
            int want = Math.min(lv, p.getMaximumPower());
            if (p.getPower() == want) return;
            p.setPower(want);
            node.setBlockData(p, true);
            return;
        }
        // 标靶才有 power。红石灯走这里会整块换成 target，拆除发送端就变成标靶。
        if (node.getType() != Material.TARGET) return;
        BlockData made = powered(lv);
        if (made == null) return;
        if (made.matches(cur)) return;
        node.setBlockData(made, true);
    }

    private static int readSignal(Block node) {
        BlockData d = node.getBlockData();
        if (d instanceof AnaloguePowerable p) return p.getPower();
        String s = d.getAsString();
        int at = s.indexOf("power=");
        if (at < 0) return -1;
        int end = at + 6;
        while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
        try {
            return Integer.parseInt(s.substring(at + 6, end));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static final BlockData[] RX_STATES = new BlockData[16];
    private static volatile boolean rxStatesBad;

    private static BlockData powered(int level) {
        if (rxStatesBad) return null;
        BlockData hit = RX_STATES[level];
        if (hit != null) return hit;
        try {
            hit = Bukkit.createBlockData("minecraft:target[power=" + level + "]");
        } catch (Throwable t) {
            rxStatesBad = true;
            return null;
        }
        RX_STATES[level] = hit;
        return hit;
    }

    public boolean isLive(Models.IoRow n) {
        return live(n);
    }

    public void togglePause(Player p, int id) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Models.IoRow row = plugin.store().ioById(id);
                if (row == null) {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c节点不存在"));
                    return;
                }
                if (!plugin.canManage(p, row.owner())) {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c这是别人的节点"));
                    return;
                }
                boolean on = !"paused".equals(row.status());
                plugin.store().setIoStatus(id, on ? "paused" : "linked");
                Models.IoRow fresh = plugin.store().ioById(id);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (fresh != null) {
                        local.put(fresh.id(), fresh);
                        refreshSign(fresh);
                    }
                    plugin.msg(p, on ? "&e已暂停（输出 0）" : "&a已恢复");
                    org.bukkit.block.Block node = ChestListener.sessionNode(plugin, p);
                    if (node != null) plugin.gui().openNodeMenu(p, node);
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c操作失败"));
            }
        });
    }

    private boolean live(Models.IoRow n) {
        if (n.pairCode() == null || n.pairCode().isBlank()) return false;
        if (n.peerServer() == null || n.peerServer().isBlank()) return false;
        if (!plugin.serverLive(n.peerServer())) return false;
        if (n.peerUpdatedMs() <= 0) return false;
        long snap = n.dbNow() > 0 ? n.dbNow() - n.peerUpdatedMs() : 0;
        long since = cacheAtMs <= 0 ? 0 : Math.max(0, System.currentTimeMillis() - cacheAtMs);
        return snap + since < plugin.ioStaleMs();
    }

    public List<String> diagLines() {
        List<String> out = new ArrayList<>();
        if (local.isEmpty()) {
            out.add("本服没有红石节点");
            return out;
        }
        for (Models.IoRow n : local.values()) {
            Block b = block(n);
            String where = n.world() + " " + n.x() + "," + n.y() + "," + n.z();
            String body = b == null ? "区块外" : (loaded(b) ? b.getType().name() : "未加载");
            if ("TX".equals(n.role())) {
                int now = (b != null && loaded(b)) ? readPower(b) : -1;
                out.add("TX " + n.unit() + " 读到" + now + " 已发" + lastSent.getOrDefault(n.id(), -1)
                        + " · " + body + " · " + where);
            } else {
                int qsize = 0;
                ReplaySession s = replaySessions.get(n.id());
                if (s != null) qsize = s.queue.size();
                out.add("RX " + n.unit() + " 对端" + clamp(n.peerLevel()) + " 输出"
                        + hold.getOrDefault(n.id(), -1) + " 在线" + (live(n) ? "是" : "否")
                        + " 队列" + qsize + " · " + body + " · " + where);
            }
        }
        return out;
    }

    private long peerLagMs(Models.IoRow n) {
        if (n.peerUpdatedMs() <= 0) return -1;
        long snap = n.dbNow() > 0 ? n.dbNow() - n.peerUpdatedMs() : 0;
        long since = cacheAtMs <= 0 ? 0 : Math.max(0, System.currentTimeMillis() - cacheAtMs);
        return snap + since;
    }

    private String signStatus(Models.IoRow n) {
        if (n.pairCode() == null || n.pairCode().isBlank()) return "idle";
        if ("RX".equals(n.role())) {
            if (!live(n)) return "HOLD 0";
            return "PWR " + hold.getOrDefault(n.id(), clamp(n.peerLevel()));
        }
        return "PWR " + lastSent.getOrDefault(n.id(), n.level());
    }

    private Models.IoRow findTx(Block b) {
        for (Models.IoRow n : local.values()) {
            if ("TX".equals(n.role()) && n.x() == b.getX() && n.y() == b.getY() && n.z() == b.getZ()
                    && n.world().equals(b.getWorld().getName())) return n;
        }
        return null;
    }

    private void signLater(Models.IoRow n) {
        long now = System.currentTimeMillis();
        Long last = signAt.get(n.id());
        if (last != null && now - last < SIGN_MIN_GAP_MS) return;
        signAt.put(n.id(), now);
        refreshSign(n, false);
    }

    private void forget(int id) {
        local.remove(id);
        lastSent.remove(id);
        lastWriteAt.remove(id);
        hold.remove(id);
        signSnap.remove(id);
        signAt.remove(id);
        badBody.remove(id);
        blockCache.remove(id);
        lastEventId.remove(id);
        ReplaySession s = replaySessions.remove(id);
        if (s != null) s.queue.clear();
    }

    static int readPower(Block b) {
        int p = b.getBlockPower();
        if (p > 0) return clamp(p);
        return (b.isBlockPowered() || b.isBlockIndirectlyPowered()) ? 1 : 0;
    }

    private void ensureBody(Models.IoRow n, Block node) {
        Material want = bodyFor(n.role());
        Material now = node.getType();
        if (now == want) return;
        if (!isIoBody(now) && !now.isAir()) {
            if (badBody.add(n.id()))
                plugin.getLogger().warning("红石节点 " + n.unit() + " 的方块已被换成 " + now
                        + "，不动它。要恢复请 /link unlink 后重新登记。");
            return;
        }
        if (node.getState() instanceof Container c) c.getInventory().clear();
        node.setType(want, false);
        badBody.remove(n.id());
        hold.remove(n.id());
    }

    static Material bodyFor(String role) {
        return "RX".equals(role) ? RX_BODY : TX_BODY;
    }

    static boolean isIoBody(Material m) {
        return m == RX_BODY || m == TX_BODY || m == Material.BARREL
                || m == Material.GRAY_CONCRETE || m == Material.RED_CONCRETE;
    }

    private static boolean paused(Models.IoRow n) {
        return n != null && "paused".equals(n.status());
    }

    static void notifyPower(Block lamp, int level) {
        int lv = clamp(level);
        for (BlockFace f : new BlockFace[]{
                BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST,
                BlockFace.UP, BlockFace.DOWN
        }) {
            Block n = lamp.getRelative(f);
            if (n.getState() instanceof Sign) continue;
            if (n.getType() == Material.REDSTONE_WIRE && n.getBlockData() instanceof AnaloguePowerable wire) {
                wire.setPower(lv);
                n.setBlockData(wire, true);
            } else {
                n.setBlockData(n.getBlockData(), true);
            }
        }
    }

    static void clearOut(Block node) {
        if (node.getState() instanceof Container c) c.getInventory().clear();
        if (node.getType() == Material.TARGET || node.getBlockData() instanceof AnaloguePowerable) {
            writeSignal(node, 0);
        }
        notifyPower(node, 0);
    }
    private static boolean loaded(Block b) {
        return b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(15, v));
    }
    private String otherServer(String pair) {
        String self = plugin.serverCode();
        String[] p = pair.split("-");
        if (p.length < 2) return "?";
        return p[0].equalsIgnoreCase(self) ? p[1] : p[0];
    }
    private Block block(Models.IoRow n) {
        Block cached = blockCache.get(n.id());
        if (cached != null) {
            if (loaded(cached)) return cached;
            blockCache.remove(n.id());
            return null;
        }
        World w = Bukkit.getWorld(n.world());
        if (w == null) return null;
        if (!w.isChunkLoaded(n.x() >> 4, n.z() >> 4)) return null;
        Block b = w.getBlockAt(n.x(), n.y(), n.z());
        blockCache.put(n.id(), b);
        return b;
    }
}
