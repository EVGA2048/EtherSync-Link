package com.etherstories.link;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class AlertNet {
    private final ESLinkPlugin plugin;
    private final AtomicLong lastId = new AtomicLong(-1);
    private final Map<String, Long> faultAt = new ConcurrentHashMap<>();
    private long lastPrune;

    public AlertNet(ESLinkPlugin plugin) { this.plugin = plugin; }

    public void resetCursor() { lastId.set(-1); }

    public void listingLocal(Player seller, String itemName, int amount, double price) {
        String detail = itemName + " x" + amount + " · " + plugin.vault().format(price);
        if (plugin.alertLocalListing()) {
            String line = ColorUtil.colorize("&bESLink &7» &f[" + plugin.serverName() + "] &e"
                    + seller.getName() + " &f上架了 &a" + detail);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (plugin.wantListingAlert(p)) p.sendMessage(line);
            }
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.store().insertAlert("listing", plugin.serverCode(), plugin.serverName(),
                        seller.getName(), detail);
            } catch (Exception e) {
                plugin.getLogger().warning("上架通知写入失败: " + e.getMessage());
            }
        });
    }

    public void poll() {
        if (!plugin.store().ready()) return;
        try {
            if (lastId.get() < 0) {
                lastId.set(plugin.store().maxAlertId());
                return;
            }
            var rows = plugin.store().alertsAfter(lastId.get(), plugin.serverCode(), 40);
            if (!rows.isEmpty()) lastId.set(rows.get(rows.size() - 1).id());
            if (!rows.isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (var r : rows) show(r);
                });
            }
            long now = System.currentTimeMillis();
            if (now - lastPrune > 60_000L) {
                lastPrune = now;
                plugin.store().pruneAlerts(2 * 60 * 60 * 1000L);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("互通通知拉取失败: " + e.getMessage());
        }
    }

    private void show(Models.AlertRow r) {
        String from = r.fromName() == null || r.fromName().isBlank()
                ? plugin.prettyName(r.fromCode()) : r.fromName();
        if ("listing".equals(r.kind())) {
            if (!plugin.alertRemoteListing()) return;
            String line = ColorUtil.colorize("&bESLink &7» &f[" + from + "] &e"
                    + r.playerName() + " &f上架了 &a" + r.detail());
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (plugin.wantListingAlert(p)) p.sendMessage(line);
            }
            return;
        }
        if ("chest".equals(r.kind())) {
            if (!plugin.alertChestAdmin()) return;
            String line = ColorUtil.colorize("&bESLink &7» &e[" + from + "] &f"
                    + r.playerName() + " 创建了" + r.detail());
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("eslink.admin")) p.sendMessage(line);
            }
            return;
        }
        if ("node".equals(r.kind())) deliverNode(r, from);
    }

    public void nodeFault(String kind, Models.ChestRow box, String status, String extra) {
        if (box == null) return;
        String ck = kind + ":" + box.id() + ":" + status;
        long now = System.currentTimeMillis();
        Long last = faultAt.get(ck);
        if (last != null && now - last < 15_000L) return;
        faultAt.put(ck, now);
        String pair = box.pairCode() == null ? "" : box.pairCode();
        String why = why(status, extra);
        String detail = kind + ";" + box.id() + ";" + box.unit() + ";" + status + ";"
                + clip(extra) + ";" + pair;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.store().insertAlert("node", plugin.serverCode(), plugin.serverName(), box.unit(), detail);
                List<UUID> who = plugin.store().watchers(kind, box.id(), pair);
                Bukkit.getScheduler().runTask(plugin, () -> tell(who, box.unit(), why, plugin.serverName()));
            } catch (Exception e) {
                plugin.getLogger().warning("节点通知失败: " + e.getMessage());
            }
        });
    }

    private void deliverNode(Models.AlertRow r, String from) {
        String[] p = r.detail() == null ? new String[0] : r.detail().split(";", 6);
        if (p.length < 4) return;
        String kind = p[0];
        int nodeId;
        try {
            nodeId = Integer.parseInt(p[1]);
        } catch (Exception e) {
            return;
        }
        String unit = p[2];
        String status = p[3];
        String extra = p.length > 4 ? p[4] : "";
        String pair = p.length > 5 ? p[5] : "";
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<UUID> who = plugin.store().watchers(kind, nodeId, pair);
                Bukkit.getScheduler().runTask(plugin, () -> tell(who, unit, why(status, extra), from));
            } catch (Exception ignored) {
            }
        });
    }

    private void tell(List<UUID> who, String unit, String why, String from) {
        if (who == null || who.isEmpty()) return;
        String src = from == null || from.isBlank() ? "" : "&7[" + from + "] ";
        String line = src + "&e互通箱 UNIT " + unit + " &f" + why;
        for (UUID u : who) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline()) plugin.msg(p, line);
        }
    }

    private static String why(String status, String extra) {
        if ("missing".equals(status)) {
            return "无此物品" + (extra == null || extra.isBlank() ? "" : ": " + extra);
        }
        if ("bounce".equals(status)) {
            return "等待写入发送端回退箱" + (extra == null || extra.isBlank() ? "" : ": " + extra);
        }
        if ("returned".equals(status)) {
            return "货物已回到回退箱" + (extra == null || extra.isBlank() ? "" : ": " + extra);
        }
        if ("noback".equals(status)) return "无回退箱";
        if ("backfull".equals(status)) return "回退箱已满";
        if ("jammed".equals(status)) return "满载";
        return status == null ? "异常" : status;
    }

    private static String clip(String s) {
        if (s == null) return "";
        s = s.replace(";", ",");
        return s.length() > 80 ? s.substring(0, 80) : s;
    }
}
