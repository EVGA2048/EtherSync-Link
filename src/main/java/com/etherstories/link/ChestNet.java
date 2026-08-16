package com.etherstories.link;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ChestNet {
    private final ESLinkPlugin plugin;
    private final Map<Long, Long> soundAt = new ConcurrentHashMap<>();
    private final AtomicBoolean scanning = new AtomicBoolean();
    private final Set<Integer> txBusy = ConcurrentHashMap.newKeySet();
    private final Set<Long> qBusy = ConcurrentHashMap.newKeySet();
    private final Map<Integer, String> signSnap = new ConcurrentHashMap<>();
    private final Map<Integer, Long> heavyReadyAt = new ConcurrentHashMap<>();
    private final Map<Integer, String> savedFace = new ConcurrentHashMap<>();
    private final java.util.Set<String> lackNotified = ConcurrentHashMap.newKeySet();
    private volatile List<Models.ChestRow> cached = List.of();

    public ChestNet(ESLinkPlugin plugin) { this.plugin = plugin; }

    public void tick() {
        if (!plugin.store().ready()) return;
        if (!scanning.compareAndSet(false, true)) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var chests = plugin.store().chestsOn(plugin.serverCode());
                var pending = plugin.store().pendingTo(plugin.serverCode());
                var bounced = plugin.store().bounceTo(plugin.serverCode());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        cached = List.copyOf(chests);
                        for (var c : chests) {
                            String snap = c.unit() + "|" + c.peerUnit() + "|" + (c.pairCode() == null ? "" : c.pairCode())
                                    + "|" + c.status() + "|" + c.role() + "|" + c.bounceId();
                            if (!snap.equals(signSnap.get(c.id()))) {
                                refreshSign(c);
                                signSnap.put(c.id(), snap);
                            }
                            if ("TX".equals(c.role()) && c.pairCode() != null && !c.pairCode().isBlank()
                                    && !"paused".equals(c.status())) {
                                drainTx(c);
                            }
                        }
                        for (var q : pending) deliver(q);
                        for (var q : bounced) returnBounce(q);
                    } finally {
                        scanning.set(false);
                    }
                });
            } catch (Exception e) {
                scanning.set(false);
                plugin.getLogger().warning("扫队列失败: " + e.getMessage());
            }
        });
    }

    public void refreshSign(Models.ChestRow c) {
        if (c == null) return;
        try {
            refreshSign0(c);
        } catch (Throwable t) {
            plugin.getLogger().warning("刷新牌子失败 UNIT " + c.unit() + ": " + t.getMessage());
        }
    }

    private void refreshSign0(Models.ChestRow c) {
        Block b = block(c);
        if (b == null) return;
        Sign sign = ChestListener.findSign(b);
        if (sign == null) sign = ChestListener.ensureSign(b, ChestListener.parseFace(c.signFace()));
        if (sign != null) rememberSignFace(c, ChestListener.faceOf(sign));
        String other = (c.pairCode() == null || c.pairCode().isBlank()) ? "" : otherServer(c.pairCode());
        String via = NodeSigns.via(plugin, c.role(), c.pairCode(), other);
        String peer = c.peerUnit();
        if ("BK".equals(c.role()) && c.bounceId() > 0) {
            for (var x : cached) {
                if (x.id() == c.bounceId()) {
                    peer = x.unit();
                    break;
                }
            }
            via = peer.isBlank() ? "未绑定发送" : "退回 " + peer;
        }
        String displayStatus = c.status();
        if (!"BK".equals(c.role()) && !other.isBlank() && !plugin.serverLive(other))
            displayStatus = "HOLD";
        int wait = NodeSigns.trouble(displayStatus) ? 0 : waitSec(c.id());
        NodeSigns.write(sign, "chest", c.role(), c.unit(), peer, via, displayStatus, wait);
        if (b.getState() instanceof Chest chest) {
            chest.setCustomName(NodeSigns.chestTitle(c.role(), c.unit(), peer, displayStatus, wait));
            chest.update();
        }
    }

    public void paint() {
        for (Models.ChestRow c : cached) refreshSign(c);
    }

    private void rememberSignFace(Models.ChestRow c, BlockFace face) {
        if (c == null || face == null) return;
        if (face.name().equalsIgnoreCase(c.signFace())) return;
        if (face.name().equalsIgnoreCase(savedFace.get(c.id()))) return;
        savedFace.put(c.id(), face.name());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.store().setSignFace(c.id(), face.name());
            } catch (Exception ignored) {
            }
        });
    }

    public Models.ChestRow cachedAt(String world, int x, int y, int z) {
        for (Models.ChestRow c : cached) {
            if (c.x() == x && c.y() == y && c.z() == z && c.world().equals(world)) return c;
        }
        World w = Bukkit.getWorld(world);
        if (w == null) return null;
        Block other = ChestListener.otherHalf(w.getBlockAt(x, y, z));
        if (other == null) return null;
        for (Models.ChestRow c : cached) {
            if (c.x() == other.getX() && c.y() == other.getY() && c.z() == other.getZ() && c.world().equals(world))
                return c;
        }
        return null;
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
        Block chest = ChestListener.sessionChest(plugin, p);
        if (chest == null) {
            plugin.msg(p, "&c请看准目标箱，再输入 /link chest");
            return;
        }
        ChestListener.clearLook(plugin, p);
        ChestListener.ensureSign(chest, ChestListener.faceFromPlayer(p, chest));
        BlockFace placed = ChestListener.faceOf(ChestListener.findSign(chest));
        Block other = ChestListener.otherHalf(chest);
        String ow = other == null ? null : other.getWorld().getName();
        int ox = other == null ? 0 : other.getX();
        int oy = other == null ? 0 : other.getY();
        int oz = other == null ? 0 : other.getZ();
        boolean doubleChest = other != null;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (plugin.store().banned(plugin.serverCode(), p.getUniqueId())) {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c你已被禁止放置运输箱"));
                    return;
                }
                Models.IoRow ioHere = plugin.store().ioAt(plugin.serverCode(),
                        chest.getWorld().getName(), chest.getX(), chest.getY(), chest.getZ());
                if (ioHere != null) {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c该坐标已是红石节点 UNIT " + ioHere.unit()));
                    return;
                }
                if (ow != null) {
                    Models.ChestRow half = plugin.store().chestAt(plugin.serverCode(), ow, ox, oy, oz);
                    if (half != null) {
                        Bukkit.getScheduler().runTask(plugin, () ->
                                plugin.msg(p, "&c这是大箱的另一半，已经是 UNIT " + half.unit()));
                        return;
                    }
                }
                Models.ChestRow old = plugin.store().chestAt(plugin.serverCode(),
                        chest.getWorld().getName(), chest.getX(), chest.getY(), chest.getZ());
                if (old != null && !plugin.canManage(p, old.owner())) {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c这是别人的互通箱"));
                    return;
                }
                if (old != null) plugin.store().deleteChest(old.id());
                int id = plugin.store().insertChest(null, plugin.serverCode(), role,
                        chest.getWorld().getName(), chest.getX(), chest.getY(), chest.getZ(),
                        p.getUniqueId(), p.getName());
                if (placed != null) plugin.store().setSignFace(id, placed.name());
                Models.ChestRow row = plugin.store().chestById(id);
                String loc = chest.getWorld().getName() + " " + chest.getX() + " " + chest.getY() + " " + chest.getZ();
                String unit = row == null ? Units.code(id) : row.unit();
                String detail = role + " UNIT " + unit + " @ " + loc;
                try {
                    plugin.store().insertAlert("chest", plugin.serverCode(), plugin.serverName(), p.getName(), detail);
                    plugin.store().setWatch(p.getUniqueId(), p.getName(), "chest", id, true);
                } catch (Exception ignored) {
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.markRxNode(chest, role.equals("RX"));
                    if (row != null) refreshSign(row);
                    Sessions.State st = plugin.sessions().of(p);
                    st.pairKind = "chest";
                    st.pairRole = role;
                    st.pendingChestId = id;
                    plugin.notifyAdmins("&e" + p.getName() + " &f登记 " + role + " UNIT " + unit + " &8" + loc);
                    plugin.msg(p, "&a已登记 " + ESLinkPlugin.roleCn(role) + "  UNIT " + unit + "。打开箱子可见编号。接下来选择对端空闲节点。");
                    if (doubleChest) plugin.msg(p, "&7大箱按一整口算，两边漏斗都走这一口。");
                    if ("TX".equals(role)) plugin.msg(p, "&e发送箱必须绑回退箱：打开菜单点「绑定回退箱」，再对着一口空箱子右键。");
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
        Block chest = ChestListener.sessionChest(plugin, p);
        if (chest == null) {
            plugin.msg(p, "&c请看准一口跨服运输箱");
            return;
        }
        ChestListener.clearLook(plugin, p);
        Block other = ChestListener.otherHalf(chest);
        String ow = other == null ? null : other.getWorld().getName();
        int ox = other == null ? 0 : other.getX();
        int oy = other == null ? 0 : other.getY();
        int oz = other == null ? 0 : other.getZ();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Models.ChestRow found = plugin.store().chestAt(plugin.serverCode(),
                        chest.getWorld().getName(), chest.getX(), chest.getY(), chest.getZ());
                if (found == null && ow != null)
                    found = plugin.store().chestAt(plugin.serverCode(), ow, ox, oy, oz);
                if (found == null) {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c这不是跨服运输箱"));
                    return;
                }
                if (!plugin.canManage(p, found.owner())) {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c这是别人的互通箱"));
                    return;
                }
                plugin.store().deleteChest(found.id());
                signSnap.remove(found.id());
                Models.ChestRow row = found;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.markRxNode(block(row), false);
                    if (chest.getState() instanceof Chest ch) {
                        ch.setCustomName(null);
                        ch.update();
                    }
                    Sign sign = ChestListener.findSign(chest);
                    if (sign != null) sign.getBlock().setType(org.bukkit.Material.AIR);
                    plugin.msg(p, "&7已拆除 " + row.role() + " UNIT " + row.unit());
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c取消失败"));
            }
        });
    }

    public void togglePause(Player p, int id) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Models.ChestRow row = plugin.store().chestById(id);
                if (row == null) {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c节点不存在"));
                    return;
                }
                if (!plugin.canManage(p, row.owner())) {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c这是别人的节点"));
                    return;
                }
                boolean on = !"paused".equals(row.status());
                plugin.store().setChestStatus(id, on ? "paused" : "linked");
                Models.ChestRow fresh = plugin.store().chestById(id);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (fresh != null) refreshSign(fresh);
                    plugin.msg(p, on ? "&e已暂停传输" : "&a已恢复传输");
                    org.bukkit.block.Block node = ChestListener.sessionNode(plugin, p);
                    if (node != null) plugin.gui().openNodeMenu(p, node);
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c操作失败"));
            }
        });
    }

    public int clearBarriers(org.bukkit.block.Block node) {
        if (node == null || !(node.getState() instanceof Chest chest)) return 0;
        int n = 0;
        var inv = ChestListener.chestInv(chest);
        if (inv == null) return 0;
        ItemStack[] all = inv.getContents();
        for (int i = 0; i < all.length; i++) {
            if (Items.hopperLocked(plugin, all[i])) {
                n += Math.max(1, all[i].getAmount());
                inv.setItem(i, null);
            }
        }
        return n;
    }

    public void setFilter(Player p, int id, String filter) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Models.ChestRow row = plugin.store().chestById(id);
                if (row == null) {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c节点不存在"));
                    return;
                }
                if (!plugin.canManage(p, row.owner())) {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c这是别人的节点"));
                    return;
                }
                if (!"TX".equals(row.role())) {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c只有发送端能设过滤"));
                    return;
                }
                String f = filter == null ? "" : filter.trim();
                plugin.store().setChestFilter(id, f);
                Models.ChestRow fresh = plugin.store().chestById(id);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (fresh != null) refreshSign(fresh);
                    plugin.msg(p, f.isBlank() ? "&7已取消过滤，全部可送" : "&a只送 " + f);
                    org.bukkit.block.Block node = ChestListener.sessionNode(plugin, p);
                    if (node != null) plugin.gui().openNodeMenu(p, node);
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c设置失败"));
            }
        });
    }

    /**
     * 发货前置检查：对面注册表里没有这个物品就别收走，省掉发出去再退回来那一圈。
     * 清单没同步到时一律放行——没数据不能成为卡住发货的理由。
     */
    private boolean peerLacks(Models.ChestRow tx, ItemStack it) {
        String pair = tx.pairCode();
        if (pair == null || pair.isBlank()) return false;
        String peer = otherServer(pair);
        if (peer == null || peer.isBlank() || "?".equals(peer)) return false;
        if (!Compat.haveList(peer)) return false;
        String miss = Compat.firstMissing(peer, Items.itemKey(it), NestedItems.csv(it));
        if (miss == null) return false;
        if (lackNotified.add(tx.id() + "|" + miss)) {
            plugin.getLogger().info("发货前置检查拦下 " + Items.itemKey(it)
                    + "：" + peer + " 没有 " + miss);
            plugin.alerts().nodeFault("chest", tx, "hold",
                    plugin.prettyName(peer) + "没有 " + miss);
        }
        return true;
    }

    private String otherServer(String pair) {
        String self = plugin.serverCode();
        String[] p = pair.split("-");
        if (p.length < 2) return "?";
        return p[0].equalsIgnoreCase(self) ? p[1] : p[0];
    }

    private int waitSec(int chestId) {
        Long at = heavyReadyAt.get(chestId);
        if (at == null) return 0;
        long left = at - System.currentTimeMillis();
        if (left <= 0) return 0;
        return (int) Math.ceil(left / 1000.0);
    }

    private boolean takeHeaviesNow(Models.ChestRow tx, List<ItemStack> heavies) {
        int delay = heavyDelaySec(heavies);
        if (delay <= 0) return true;
        long now = System.currentTimeMillis();
        Long ready = heavyReadyAt.get(tx.id());
        if (ready == null) {
            heavyReadyAt.put(tx.id(), now + delay * 1000L);
            refreshSign(tx);
            return false;
        }
        return now >= ready;
    }

    private int heavyDelaySec(List<ItemStack> heavies) {
        int base = plugin.chestHeavyDelaySeconds();
        if (base <= 0) return 0;
        int inners = 0;
        for (ItemStack it : heavies) {
            List<ItemStack> inn = NestedItems.inners(it);
            if (inn != null) inners += inn.size();
        }
        int extra = Math.max(0, heavies.size() - 1) + inners / 4;
        return Math.min(plugin.chestHeavyMaxSeconds(), base + extra);
    }

    private void drainTx(Models.ChestRow c) {
        if ("BK".equals(c.role())) return;
        if (!txBusy.add(c.id())) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int pending;
            int bouncing;
            Models.ChestRow partner;
            try {
                bouncing = (c.pairCode() == null || c.pairCode().isBlank())
                        ? 0 : plugin.store().bouncePendingOnPair(c.pairCode());
                pending = plugin.store().pendingOnPair(c.pairCode());
                partner = plugin.store().partner(c.id(), c.pairCode());
            } catch (Exception e) {
                txBusy.remove(c.id());
                return;
            }
            if (partner == null) {
                txBusy.remove(c.id());
                return;
            }
            int room = plugin.chestQueueLimit() - pending;
            Models.ChestRow dest = partner;
            int bounceWait = bouncing;
            Bukkit.getScheduler().runTask(plugin, () -> {
                String why = bounceBlockReason(c, bounceWait);
                if (why != null) {
                    if (!why.equals(c.status())) setStatus(c, why);
                    txBusy.remove(c.id());
                    return;
                }
                if ("noback".equals(c.status()) || "backfull".equals(c.status())) {
                    setStatus(c, "linked");
                }
                if (room <= 0) {
                    txBusy.remove(c.id());
                    return;
                }
                takeMany(c, dest, Math.min(plugin.chestStacksPerScan(), room), bounceWait);
            });
        });
    }

    private void takeMany(Models.ChestRow tx, Models.ChestRow dest, int n, int bouncing) {
        Block b = block(tx);
        if (b == null || !(b.getState() instanceof Chest chest)) {
            txBusy.remove(tx.id());
            return;
        }
        Inventory inv = ChestListener.chestInv(chest);
        ItemStack[] contents = inv.getContents();
        List<ItemStack> lights = new ArrayList<>();
        List<Integer> lightSlots = new ArrayList<>();
        List<ItemStack> heavies = new ArrayList<>();
        List<Integer> heavySlots = new ArrayList<>();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (!ItemKeys.real(it)) continue;
            if (Items.hopperLocked(plugin, it)) continue;
            if (!plugin.allowed(it)) continue;
            if (!Items.passFilter(Items.itemKey(it), tx.itemFilter())) continue;
            if (peerLacks(tx, it)) continue;
            boolean heavy = NestedItems.containerLike(Items.itemKey(it));
            if (heavy && !NestedItems.emptyBox(it) && !ContainerSupport.allow(Items.itemKey(it))) continue;
            if (heavy) {
                heavies.add(it.clone());
                heavySlots.add(i);
            } else {
                lights.add(it.clone());
                lightSlots.add(i);
            }
        }
        List<ItemStack> sends = new ArrayList<>();
        List<Integer> slots = new ArrayList<>();
        int room = n;
        int takeL = Math.min(lights.size(), room);
        for (int i = 0; i < takeL; i++) {
            sends.add(lights.get(i));
            slots.add(lightSlots.get(i));
        }
        room -= takeL;
        if (heavies.isEmpty()) {
            if (heavyReadyAt.remove(tx.id()) != null) refreshSign(tx);
        } else if (takeHeaviesNow(tx, heavies) && room > 0) {
            int takeH = Math.min(heavies.size(), room);
            for (int i = 0; i < takeH; i++) {
                sends.add(heavies.get(i));
                slots.add(heavySlots.get(i));
            }
            heavyReadyAt.remove(tx.id());
        }
        if (sends.isEmpty()) {
            txBusy.remove(tx.id());
            return;
        }
        int reserve = 0;
        for (ItemStack send : sends) reserve += requiredBounceSlots(send, dest.serverCode());
        int free = bounceFreeSlots(tx);
        if (free < bouncing + reserve) {
            if (!"backfull".equals(tx.status())) {
                setStatus(tx, "backfull");
                beep(b.getLocation(), false);
                plugin.alerts().nodeFault("chest", tx, "backfull",
                        "需 " + reserve + " 格，剩 " + Math.max(0, free - bouncing) + " 格");
            }
            txBusy.remove(tx.id());
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<ItemStack> prepared = new ArrayList<>();
            List<Integer> preparedSlots = new ArrayList<>();
            List<String> keys = new ArrayList<>();
            List<String> names = new ArrayList<>();
            List<String> blobs = new ArrayList<>();
            List<String> nested = new ArrayList<>();
            List<String> kinds = new ArrayList<>();
            List<Integer> amounts = new ArrayList<>();
            List<Integer> returnSlots = new ArrayList<>();
            try {
                for (int i = 0; i < sends.size(); i++) {
                    ItemStack send = sends.get(i);
                    String key = Items.itemKey(send);
                    long started = System.nanoTime();
                    String b64 = ItemCodec.encode(send);
                    if (b64 == null || b64.isBlank()) {
                        plugin.getLogger().warning("编码失败，留在 TX: " + key);
                        continue;
                    }
                    String csv = NestedItems.csv(send);
                    if (NestedItems.containerLike(key)) {
                        byte[] raw = java.util.Base64.getDecoder().decode(b64);
                        if (!safeContainerBlob(raw)) {
                            plugin.getLogger().warning("容器安全格式检查失败，留在 TX: " + key
                                    + " · " + ItemNbt.kind(raw));
                            continue;
                        }
                        long ms = (System.nanoTime() - started) / 1_000_000L;
                        LinkLog.debug("容器异步编码 " + key + " " + ItemNbt.kind(raw) + " " + ms + "ms");
                    }
                    String kind = "?";
                    try {
                        kind = ItemNbt.kind(java.util.Base64.getDecoder().decode(b64));
                    } catch (Throwable ignored) {
                    }
                    if (lossyEncode(send, b64)) {
                        if (lackNotified.add(tx.id() + "|lossy|" + key)) {
                            plugin.getLogger().warning("快照失败会丢数据，留在 TX: " + key
                                    + " · " + NestedItems.componentSummary(send));
                            plugin.alerts().nodeFault("chest", tx, "hold", key + " 数据存不下来");
                        }
                        continue;
                    }
                    prepared.add(send);
                    preparedSlots.add(slots.get(i));
                    keys.add(key);
                    names.add(ItemCodec.display(send));
                    blobs.add(b64);
                    nested.add(csv);
                    kinds.add(kind);
                    amounts.add(send.getAmount());
                    returnSlots.add(requiredBounceSlots(send, dest.serverCode()));
                }
                if (prepared.isEmpty()) {
                    txBusy.remove(tx.id());
                    return;
                }
                List<Integer> queued = new ArrayList<>();
                for (int i = 0; i < prepared.size(); i++) {
                    plugin.store().enqueue(plugin.serverCode(), dest.serverCode(), tx.pairCode(),
                            keys.get(i), names.get(i), amounts.get(i),
                            blobs.get(i), nested.get(i), returnSlots.get(i));
                    queued.add(i);
                    LinkLog.debug("发 " + keys.get(i) + " x" + amounts.get(i)
                            + " " + tx.unit() + ">" + dest.serverCode() + " " + kinds.get(i));
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (int i : queued) {
                        ItemStack send = prepared.get(i);
                        int removeSlot = preparedSlots.get(i);
                        ItemStack now = inv.getItem(removeSlot);
                        int amt = send.getAmount();
                        if (now != null && now.isSimilar(send) && now.getAmount() >= amt) {
                            now.setAmount(now.getAmount() - amt);
                            if (now.getAmount() <= 0) inv.setItem(removeSlot, null);
                        } else {
                            inv.removeItem(send);
                        }
                    }
                    txBusy.remove(tx.id());
                });
            } catch (Exception e) {
                txBusy.remove(tx.id());
                plugin.getLogger().warning("TX 入队失败: " + e.getMessage());
            }
        });
    }

    private void deliver(Models.QueueRow q) {
        if (!emptyContainer(q) && ContainerSupport.pending(q.itemKey())) return;
        if (!emptyContainer(q) && !ContainerSupport.allow(q.itemKey())) {
            if (qBusy.add(q.id())) bounce(q, ContainerSupport.blockReason(q.itemKey()));
            return;
        }
        if (!qBusy.add(q.id())) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Models.ChestRow rx;
            try {
                rx = plugin.store().chestByPairRole(q.pairCode(), plugin.serverCode(), "RX");
            } catch (Exception e) {
                qBusy.remove(q.id());
                return;
            }
            if (rx == null) {
                qBusy.remove(q.id());
                return;
            }
            if ("paused".equals(rx.status())) {
                qBusy.remove(q.id());
                return;
            }
            Store.EscrowClaim escrow;
            try {
                List<java.util.UUID> tokens = ItemEnvelope.returnTokens(q.blob());
                escrow = plugin.store().reserveEscrows(tokens, plugin.serverCode());
                if (escrow == null) {
                    plugin.store().setQueueStatus(q.id(), "quarantine");
                    qBusy.remove(q.id());
                    plugin.getLogger().warning("恢复凭证无效或已使用，隔离队列 #" + q.id());
                    return;
                }
            } catch (Exception e) {
                qBusy.remove(q.id());
                return;
            }
            Models.ChestRow box = rx;
            if (NestedItems.containerLike(q.itemKey()) && !safeContainerBlob(q.blob())) {
                finishEscrows(escrow, false);
                bounce(q, "旧容器包 " + ItemNbt.kind(q.blob()) + "，为防卡服不再解码");
                return;
            }
            ItemNbt.PackedDecoded packed;
            ItemStack give;
            long decodeStarted = System.nanoTime();
            ItemEnvelope.useResolved(escrow.payloads());
            try {
                packed = ItemNbt.decodePacked(q.blob(), true);
                give = ItemNbt.packed(q.blob()) ? null
                        : ItemCodec.tryDecode(q.blob(), q.itemKey(), q.amount(), q.nestedKeys());
            } catch (Throwable t) {
                finishEscrows(escrow, false);
                qBusy.remove(q.id());
                plugin.getLogger().warning("RX 解码异常 #" + q.id() + ": "
                        + t.getClass().getSimpleName());
                return;
            } finally {
                ItemEnvelope.clearResolved();
            }
            List<ItemNbt.PackedChild> rejected = packed == null ? List.of() : packed.rejected();
            long decodeMs = (System.nanoTime() - decodeStarted) / 1_000_000L;
            if (packed == null && give == null) {
                finishEscrows(escrow, false);
                String miss = NestedItems.firstMissing(q.nestedKeys(), q.itemKey());
                String reason = ItemCodec.whyNot(q.blob(), q.itemKey(), q.nestedKeys());
                plugin.getLogger().warning("缺物品，退回发送端: " + miss
                        + " · " + reason + " · " + ItemNbt.kind(q.blob()));
                String title = q.itemName();
                if (title == null || title.isBlank()) title = miss;
                else if (miss != null && !miss.equalsIgnoreCase(q.itemKey())) title = title + " · " + miss;
                try {
                    plugin.store().setQueueStatus(q.id(), "bounce");
                } catch (Exception ignored) {
                } finally {
                    qBusy.remove(q.id());
                }
                // 退回原因以前只进收货端控制台，发货的人在聊天里只看到物品名，没法自查。
                String alertTitle = title + (reason == null || reason.isBlank() ? "" : " · " + reason);
                Bukkit.getScheduler().runTask(plugin,
                        () -> plugin.alerts().nodeFault("chest", box, "bounce", alertTitle));
                return;
            }
            LinkLog.debug("RX 异步解码 " + q.itemKey() + " " + ItemNbt.kind(q.blob())
                    + " " + decodeMs + "ms");
            Bukkit.getScheduler().runTask(plugin, () -> {
                Block b = block(box);
                if (b == null || !(b.getState() instanceof Chest chest)) {
                    finishEscrows(escrow, false);
                    jam(box, "RX丢失");
                    qBusy.remove(q.id());
                    return;
                }
                ItemStack decoded = packed == null ? give : buildPackedGuarded(packed, q, "RX");
                decoded = CrossRefs.mark(decoded, plugin.prettyName(q.fromCode()));
                if (!ItemKeys.real(decoded)) {
                    finishEscrows(escrow, false);
                    bounce(q, "本服重建容器失败，交还发送端");
                    return;
                }
                var inv = ChestListener.chestInv(chest);
                ItemStack[] snap = inv.getContents();
                var leftover = inv.addItem(decoded);
                if (!leftover.isEmpty()) {
                    inv.setContents(snap);
                    finishEscrows(escrow, false);
                    jam(box, "堵塞");
                    qBusy.remove(q.id());
                    return;
                }
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        if (rejected.isEmpty()) plugin.store().setQueueStatus(q.id(), "delivered");
                        else plugin.store().completeWithBounces(q, rejected);
                        plugin.store().setChestStatus(box.id(), "linked");
                    } catch (Exception ignored) {
                    } finally {
                        try { plugin.store().finishEscrows(escrow, true); } catch (Exception ignored) {}
                        qBusy.remove(q.id());
                    }
                });
                if (!rejected.isEmpty()) {
                    plugin.getLogger().warning("容器部分退回: " + q.itemKey() + " · "
                            + rejected.size() + " 组不兼容物品");
                    plugin.alerts().nodeFault("chest", box, "bounce",
                            q.itemName() + " 内 " + rejected.size() + " 组");
                }
                LinkLog.debug("收 " + q.itemKey() + " x" + q.amount() + " " + box.unit() + " ok");
                beep(b.getLocation(), true);
            });
        });
    }

    private void finishEscrows(Store.EscrowClaim claim, boolean delivered) {
        if (claim == null || claim.claimId().isBlank()) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.store().finishEscrows(claim, delivered);
            } catch (Exception ignored) {
            }
        });
    }

    public void beginBindBounce(Player p, int txId) {
        if (!p.hasPermission("eslink.chest")) {
            plugin.msg(p, "&c没有权限");
            return;
        }
        if (txId <= 0) {
            plugin.msg(p, "&c没拿到发送箱编号，关掉菜单再打开试一次");
            return;
        }
        plugin.sessions().of(p).bindBounceFor = txId;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            p.closeInventory();
            plugin.msg(p, "&e菜单已关。看准一口空箱子，直接右键。不要点发送箱自己。");
        });
    }

    public void bindBounce(Player p, int txId, Block bounce) {
        plugin.sessions().of(p).bindBounceFor = null;
        if (bounce == null || !(bounce.getState() instanceof Chest)) {
            plugin.msg(p, "&c请看准一口箱子");
            return;
        }
        String bw = bounce.getWorld().getName();
        int bx = bounce.getX(), by = bounce.getY(), bz = bounce.getZ();
        Block bounceBlock = bounce;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Models.ChestRow tx = plugin.store().chestById(txId);
                if (tx == null || !"TX".equals(tx.role())) {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c只给发送箱绑回退箱"));
                    return;
                }
                if (!plugin.canManage(p, tx.owner())) {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c这是别人的发送箱"));
                    return;
                }
                if (!tx.serverCode().equals(plugin.serverCode())) {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c回退箱必须在本服"));
                    return;
                }
                if (tx.world().equals(bw) && tx.x() == bx && tx.y() == by && tx.z() == bz) {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c回退箱不能和发送箱是同一口"));
                    return;
                }
                Models.IoRow ioHere = plugin.store().ioAt(plugin.serverCode(), bw, bx, by, bz);
                if (ioHere != null) {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c该坐标已是红石节点"));
                    return;
                }
                Models.ChestRow old = plugin.store().chestAt(plugin.serverCode(), bw, bx, by, bz);
                if (old != null && old.id() != tx.bounceId()) {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c这已经是互通箱 UNIT " + old.unit()));
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Block txBlock = block(tx);
                    if (txBlock != null && sameInv(txBlock, bounceBlock)) {
                        plugin.msg(p, "&c回退箱不能和发送箱是同一组大箱");
                        return;
                    }
                    ChestListener.ensureSign(bounceBlock, ChestListener.faceFromPlayer(p, bounceBlock));
                    BlockFace bf = ChestListener.faceOf(ChestListener.findSign(bounceBlock));
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            if (tx.bounceId() > 0 && (old == null || old.id() != tx.bounceId())) {
                                plugin.store().deleteChest(tx.bounceId());
                            }
                            int bkId = old != null && "BK".equals(old.role()) ? old.id()
                                    : plugin.store().insertChest(null, plugin.serverCode(), "BK",
                                    bw, bx, by, bz, p.getUniqueId(), p.getName());
                            plugin.store().bindBounce(txId, bkId);
                            if (bf != null) plugin.store().setSignFace(bkId, bf.name());
                            Models.ChestRow bk = plugin.store().chestById(bkId);
                            Models.ChestRow fresh = plugin.store().chestById(txId);
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (bk != null) refreshSign(bk);
                                if (fresh != null) refreshSign(fresh);
                                plugin.msg(p, "&a已绑定回退箱 UNIT " + (bk == null ? bkId : bk.unit()));
                            });
                        } catch (Exception e) {
                            Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c绑定失败: " + e.getMessage()));
                        }
                    });
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c绑定失败"));
            }
        });
    }

    public void unbindBounce(Player p, int txId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Models.ChestRow tx = plugin.store().chestById(txId);
                if (tx == null || !"TX".equals(tx.role())) return;
                if (!plugin.canManage(p, tx.owner())) {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c这是别人的节点"));
                    return;
                }
                if (tx.bounceId() <= 0) {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&7还没有回退箱"));
                    return;
                }
                int bkId = tx.bounceId();
                Models.ChestRow bk = plugin.store().chestById(bkId);
                plugin.store().deleteChest(bkId);
                Models.ChestRow fresh = plugin.store().chestById(txId);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (bk != null) {
                        Block b = block(bk);
                        if (b != null && b.getState() instanceof Chest ch) {
                            ch.setCustomName(null);
                            ch.update();
                        }
                        Sign sign = b == null ? null : ChestListener.findSign(b);
                        if (sign != null) sign.getBlock().setType(org.bukkit.Material.AIR);
                    }
                    if (fresh != null) refreshSign(fresh.withStatus("noback"));
                    plugin.msg(p, "&7已解除回退箱，发送会暂停");
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c解除失败"));
            }
        });
    }

    private String bounceBlockReason(Models.ChestRow tx, int bouncing) {
        if (tx.bounceId() <= 0) return "noback";
        Models.ChestRow bk = null;
        for (var x : cached) {
            if (x.id() == tx.bounceId()) {
                bk = x;
                break;
            }
        }
        Block b = bk == null ? null : block(bk);
        if (b == null || !(b.getState() instanceof Chest chest)) return "noback";
        int empty = emptySlots(chest.getInventory());
        if (empty <= bouncing) return "backfull";
        return null;
    }

    private int bounceFreeSlots(Models.ChestRow tx) {
        if (tx == null || tx.bounceId() <= 0) return 0;
        for (Models.ChestRow c : cached) {
            if (c.id() != tx.bounceId()) continue;
            Block b = block(c);
            if (b != null && b.getState() instanceof Chest chest)
                return emptySlots(chest.getInventory());
        }
        return 0;
    }

    /**
     * 部分退回是一件不兼容内含物建一行、各占一格，所以要按"可能退回来的件数"预留。
     * 按整箱件数预留（满潜影盒要 27 格）会让单箱回退箱永远凑不出来，放两个东西就报满。
     */
    private static int requiredBounceSlots(ItemStack item, String peerCode) {
        if (!ItemKeys.real(item)) return 1;
        if (!NestedItems.containerLike(Items.itemKey(item))) return 1;
        List<ItemStack> inner = NestedItems.inners(item);
        if (inner == null || inner.isEmpty()) return 1;
        if (Compat.haveList(peerCode)) {
            int miss = 0;
            for (ItemStack in : inner) {
                if (ItemKeys.real(in) && Compat.missing(peerCode, Items.itemKey(in))) miss++;
            }
            return Math.max(1, miss);
        }
        // 没有对端清单只能估：原版内含物对面基本都有，只有模组物品才可能被退。
        int modded = 0;
        for (ItemStack in : inner) {
            if (!ItemKeys.real(in)) continue;
            String k = Items.itemKey(in);
            if (k != null && !k.startsWith("minecraft:")) modded++;
        }
        return Math.max(1, modded);
    }

    private static int emptySlots(Inventory inv) {
        int n = 0;
        for (ItemStack it : inv.getStorageContents()) {
            if (it == null || it.getType().isAir()) n++;
        }
        return n;
    }

    private static boolean sameInv(Block a, Block b) {
        if (a.getWorld() == b.getWorld() && a.getX() == b.getX() && a.getY() == b.getY() && a.getZ() == b.getZ())
            return true;
        if (!(a.getState() instanceof Chest ca) || !(b.getState() instanceof Chest cb)) return false;
        var ha = ca.getInventory().getHolder();
        var hb = cb.getInventory().getHolder();
        return ha != null && ha.equals(hb);
    }

    private void setStatus(Models.ChestRow c, String st) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.store().setChestStatus(c.id(), st);
            } catch (Exception ignored) {
            }
        });
        refreshSign(c.withStatus(st));
    }

    private void returnBounce(Models.QueueRow q) {
        if (!emptyContainer(q) && ContainerSupport.pending(q.itemKey())) return;
        if (!emptyContainer(q) && !ContainerSupport.allow(q.itemKey())) {
            if (qBusy.add(q.id())) quarantine(q, ContainerSupport.blockReason(q.itemKey()));
            return;
        }
        if (!qBusy.add(q.id())) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Models.ChestRow tx;
            Models.ChestRow bk;
            try {
                tx = plugin.store().chestByPairRole(q.pairCode(), plugin.serverCode(), "TX");
                bk = (tx == null || tx.bounceId() <= 0) ? null : plugin.store().chestById(tx.bounceId());
            } catch (Exception e) {
                qBusy.remove(q.id());
                return;
            }
            if (tx == null || bk == null) {
                qBusy.remove(q.id());
                return;
            }
            Models.ChestRow box = bk;
            Models.ChestRow send = tx;
            if (NestedItems.containerLike(q.itemKey()) && !safeContainerBlob(q.blob())) {
                quarantine(q, "旧回退容器 " + ItemNbt.kind(q.blob()) + "，为防卡服不再解码");
                return;
            }
            long decodeStarted = System.nanoTime();
            ItemNbt.PackedDecoded packed = ItemNbt.decodePacked(q.blob(), false);
            ItemStack ordinary = ItemNbt.packed(q.blob()) ? null
                    : ItemCodec.tryDecode(q.blob(), q.itemKey(), q.amount(), q.nestedKeys());
            if (packed == null && ordinary == null) {
                plugin.getLogger().warning("退回失败: " + q.itemKey()
                        + " · " + ItemCodec.whyNot(q.blob(), q.itemKey(), q.nestedKeys()));
                qBusy.remove(q.id());
                return;
            }
            LinkLog.debug("BK 异步解码 " + q.itemKey() + " " + ItemNbt.kind(q.blob()) + " "
                    + ((System.nanoTime() - decodeStarted) / 1_000_000L) + "ms");
            Bukkit.getScheduler().runTask(plugin, () -> {
                Block b = block(box);
                if (b == null || !(b.getState() instanceof Chest chest)) {
                    setStatus(send, "noback");
                    qBusy.remove(q.id());
                    return;
                }
                ItemStack decoded = packed == null ? ordinary : buildPackedGuarded(packed, q, "BK");
                if (!ItemKeys.real(decoded)) {
                    quarantine(q, "回退容器构建失败，已熔断");
                    return;
                }
                var inv = chest.getInventory();
                ItemStack[] snap = inv.getContents();
                var leftover = inv.addItem(decoded);
                if (!leftover.isEmpty()) {
                    inv.setContents(snap);
                    if (!"backfull".equals(send.status())) beep(b.getLocation(), false);
                    setStatus(send, "backfull");
                    qBusy.remove(q.id());
                    return;
                }
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        plugin.store().setQueueStatus(q.id(), "returned");
                    } catch (Exception ignored) {
                    } finally {
                        qBusy.remove(q.id());
                    }
                });
                beep(b.getLocation(), true);
                LinkLog.debug("回退 " + q.itemKey() + " x" + q.amount() + " -> BK");
                plugin.alerts().nodeFault("chest", send, "returned", q.itemName());
            });
        });
    }

    private ItemStack buildPackedGuarded(ItemNbt.PackedDecoded packed, Models.QueueRow q, String phase) {
        long started = System.nanoTime();
        ItemStack item = ItemNbt.buildPacked(packed);
        long ms = (System.nanoTime() - started) / 1_000_000L;
        if (!ItemKeys.real(item) || ms > ContainerSupport.BUILD_BUDGET_MS) {
            String why = phase + " #" + q.id() + " " + q.itemKey()
                    + (ItemKeys.real(item) ? " 构建过慢 " + ms + "ms" : " 构建失败");
            ContainerSupport.trip(why);
            plugin.getLogger().severe("容器传输已熔断: " + why);
        } else {
            LinkLog.debug(phase + " 容器重建 " + q.itemKey() + " " + ms + "ms");
        }
        return item;
    }

    /**
     * 编码退化成只剩注册名，但物品身上明明有数据：发过去就是个空壳。
     * 与其让玩家收到一块空白剪切板，不如把东西留在发送箱里。
     */
    private static boolean lossyEncode(ItemStack send, String b64) {
        if (!send.hasItemMeta()) return false;
        try {
            return !ItemNbt.rich(java.util.Base64.getDecoder().decode(b64));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 空盒子没有内含要重建，自检没跑或没过都不该拦。 */
    private static boolean emptyContainer(Models.QueueRow q) {
        return NestedItems.emptyContents(q.itemKey(), q.nestedKeys());
    }

    /** ESN 拆装包或本插件的整包快照都行；裸 Bukkit 字节不认，那是卡过服的老格式。 */
    private static boolean safeContainerBlob(byte[] blob) {
        return ItemNbt.packed(blob) || ItemNbt.ours(blob) || ItemEnvelope.ours(blob);
    }

    /** 收件方处理不了：原服一定重建得出来，交还给它，不要变成死件。 */
    private void bounce(Models.QueueRow q, String reason) {
        plugin.getLogger().warning("退回发送端 #" + q.id() + " " + q.itemKey() + ": " + reason);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.store().setQueueStatus(q.id(), "bounce");
            } catch (Exception e) {
                plugin.getLogger().warning("标记退回失败 #" + q.id() + ": " + e.getMessage());
            } finally {
                qBusy.remove(q.id());
            }
        });
    }

    private void quarantine(Models.QueueRow q, String reason) {
        plugin.getLogger().warning("隔离队列 #" + q.id() + " " + q.itemKey() + ": " + reason);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.store().setQueueStatus(q.id(), "quarantine");
            } catch (Exception e) {
                plugin.getLogger().warning("隔离队列状态写入失败 #" + q.id() + ": " + e.getMessage());
            } finally {
                qBusy.remove(q.id());
            }
        });
    }

    private void jam(Models.ChestRow c, String why) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.store().setChestStatus(c.id(), "jammed");
            } catch (Exception ignored) {
            }
        });
        Block b = block(c);
        if (b != null) beep(b.getLocation(), false);
        refreshSign(c.withStatus("jammed"));
        plugin.alerts().nodeFault("chest", c, "jammed", why);
    }

    private void beep(Location loc, boolean ok) {
        long now = System.currentTimeMillis();
        long cd = plugin.getConfig().getLong("sound-cooldown-ticks", 40) * 50L;
        long key = loc.getBlockX() * 73856093L ^ loc.getBlockY() * 19349663L ^ loc.getBlockZ() * 83492791L;
        Long last = soundAt.get(key);
        if (last != null && now - last < cd) return;
        soundAt.put(key, now);
        Sound s = ok ? Sound.BLOCK_NOTE_BLOCK_CHIME : Sound.BLOCK_NOTE_BLOCK_BASS;
        loc.getWorld().playSound(loc, s, 0.8f, ok ? 1.4f : 0.6f);
    }

    private Block block(Models.ChestRow c) {
        World w = Bukkit.getWorld(c.world());
        if (w == null) return null;
        return w.getBlockAt(c.x(), c.y(), c.z());
    }
}
