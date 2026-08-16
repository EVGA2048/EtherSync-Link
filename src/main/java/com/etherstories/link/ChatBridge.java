package com.etherstories.link;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ChatBridge {
    static final String MSG = "\u0001MSG\u0001";
    private final ESLinkPlugin plugin;
    private final NamespacedKey modeKey;
    private final NamespacedKey mutePlayersKey;
    private final NamespacedKey muteServersKey;
    private final AtomicLong lastId = new AtomicLong(-1);
    private long lastPrune;
    private final Map<UUID, Deque<Long>> sentAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRemind = new ConcurrentHashMap<>();

    public ChatBridge(ESLinkPlugin plugin) {
        this.plugin = plugin;
        this.modeKey = new NamespacedKey(plugin, "chat");
        this.mutePlayersKey = new NamespacedKey(plugin, "mute_p");
        this.muteServersKey = new NamespacedKey(plugin, "mute_s");
    }

    public void resetCursor() {
        lastId.set(-1);
    }

    public void notice(Player p, String text) {
        ChatMsg.notice(p, text);
    }

    public boolean isAll(Player p) {
        String v = p.getPersistentDataContainer().get(modeKey, PersistentDataType.STRING);
        if (v == null || v.isBlank()) {
            return "all".equalsIgnoreCase(plugin.getConfig().getString("chat.default", "local"));
        }
        return "all".equalsIgnoreCase(v);
    }

    public void setAll(Player p, boolean all) {
        p.getPersistentDataContainer().set(modeKey, PersistentDataType.STRING, all ? "all" : "local");
        if (all) {
            ChatMsg.notice(p,
                    ChatMsg.text("聊天将发到全部互通服。发言太快会拦截并在聊天栏提醒。"),
                    ChatMsg.click(" [关互通]", "/link chat local", "点此改回仅本服"));
        } else {
            notice(p, "聊天只在本服，不会传到其他服务器。");
        }
    }

    public void toggle(Player p) {
        setAll(p, !isAll(p));
    }

    public void whisper(Player p, String target, String raw) {
        if (!plugin.getConfig().getBoolean("chat.enabled", true)) {
            notice(p, "互通聊天已关闭");
            return;
        }
        if (!plugin.store().ready()) {
            notice(p, "数据库未连接");
            return;
        }
        String name = sanitize(target);
        String msg = keepColor(raw);
        int max = Math.max(8, plugin.getConfig().getInt("chat.max-length", 128));
        if (msg.length() > max) msg = msg.substring(0, max);
        if (name.isEmpty() || msg.isBlank()) {
            notice(p, "用法: /link msg 玩家 内容");
            return;
        }
        if (name.equalsIgnoreCase(p.getName())) {
            notice(p, "不能私聊自己");
            return;
        }
        if (tooFast(p)) {
            remindFast(p);
            return;
        }
        String text = msg;
        String wire = MSG + name + "\u0001" + text;
        ChatMsg.notice(p, ChatMsg.legacy("&d→ " + name + "&f: " + text));
        Player dest = Bukkit.getPlayer(name);
        if (dest != null && dest.isOnline()) {
            deliverWhisper(dest, plugin.serverCode(), plugin.serverName(), p.getName(), text);
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (plugin.store().banned(plugin.serverCode(), p.getUniqueId())) return;
                plugin.store().insertChat(plugin.serverCode(), plugin.serverName(),
                        p.getUniqueId(), p.getName(), wire, null, null, null, null);
            } catch (Exception e) {
                plugin.getLogger().warning("私聊发送失败: " + e.getMessage());
            }
        });
    }

    public void send(Player p, String raw, ItemStack item) {
        if (!plugin.getConfig().getBoolean("chat.enabled", true)) return;
        if (!plugin.store().ready()) return;
        String msg = keepColor(raw);
        int max = Math.max(8, plugin.getConfig().getInt("chat.max-length", 128));
        if (msg.length() > max) msg = msg.substring(0, max);
        if (msg.isBlank()) return;
        if (tooFast(p)) {
            remindFast(p);
            return;
        }
        boolean wantItem = plugin.getConfig().getBoolean("chat.item", true) && ItemChat.hasToken(msg)
                && item != null && !item.getType().isAir();
        String text = msg;
        String itemKey = wantItem ? Items.itemKey(item) : null;
        String itemName = wantItem ? ItemCodec.display(item) : null;
        Integer itemAmt = wantItem ? item.getAmount() : null;
        String itemB64 = wantItem ? ItemCodec.encode(item) : null;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (plugin.store().banned(plugin.serverCode(), p.getUniqueId())) return;
                plugin.store().insertChat(plugin.serverCode(), plugin.serverName(),
                        p.getUniqueId(), p.getName(), text, itemKey, itemName, itemAmt, itemB64);
            } catch (Exception e) {
                plugin.getLogger().warning("互通聊天发送失败: " + e.getMessage());
            }
        });
    }

    private boolean tooFast(Player p) {
        long now = System.currentTimeMillis();
        long window = Math.max(2, plugin.getConfig().getLong("chat.fast-window-seconds", 8)) * 1000L;
        int max = Math.max(2, plugin.getConfig().getInt("chat.fast-count", 3));
        Deque<Long> d = sentAt.computeIfAbsent(p.getUniqueId(), u -> new ArrayDeque<>());
        synchronized (d) {
            d.addLast(now);
            while (!d.isEmpty() && now - d.peekFirst() > window) d.pollFirst();
            return d.size() > max;
        }
    }

    private void remindFast(Player p) {
        long now = System.currentTimeMillis();
        long cd = Math.max(5, plugin.getConfig().getLong("chat.fast-remind-seconds", 25)) * 1000L;
        Long last = lastRemind.get(p.getUniqueId());
        if (last != null && now - last < cd) return;
        lastRemind.put(p.getUniqueId(), now);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            ChatMsg.notice(p,
                    ChatMsg.text("你开着互通聊天，发言太快，这条没有传到其他服。"),
                    ChatMsg.click(" [关闭互通]", "/link chat local", "改回仅本服"));
        });
    }

    public boolean mutedPlayer(Player viewer, String name) {
        if (name == null) return false;
        return csv(viewer, mutePlayersKey).contains(name.toLowerCase(Locale.ROOT));
    }

    public boolean mutedServer(Player viewer, String code) {
        if (code == null) return false;
        List<String> list = csv(viewer, muteServersKey);
        if (list.contains(code.toLowerCase(Locale.ROOT))) return true;
        return list.contains(plugin.prettyName(code).toLowerCase(Locale.ROOT));
    }

    public void ignorePlayer(Player p, String name) {
        name = sanitize(name);
        if (name.isEmpty()) {
            notice(p, "用法: /link ignore player 玩家名");
            return;
        }
        addCsv(p, mutePlayersKey, name.toLowerCase(Locale.ROOT));
        ChatMsg.notice(p,
                ChatMsg.text("已屏蔽 " + name + " 的互通消息。"),
                ChatMsg.click(" [取消屏蔽]", "/link unignore player " + name, "重新接收此人"));
    }

    public void ignoreServer(Player p, String code) {
        String resolved = plugin.resolveServerCode(code);
        if (resolved.isEmpty()) {
            notice(p, "用法: /link ignore server 服务器名");
            return;
        }
        addCsv(p, muteServersKey, resolved.toLowerCase(Locale.ROOT));
        ChatMsg.notice(p,
                ChatMsg.text("已屏蔽 " + plugin.prettyName(resolved) + " 的互通消息。"),
                ChatMsg.click(" [取消屏蔽]", "/link unignore server " + resolved, "重新接收该服"));
    }

    public void unignorePlayer(Player p, String name) {
        name = sanitize(name);
        removeCsv(p, mutePlayersKey, name.toLowerCase(Locale.ROOT));
        notice(p, "已取消屏蔽 " + name + "。");
    }

    public void unignoreServer(Player p, String code) {
        String resolved = plugin.resolveServerCode(code);
        removeCsv(p, muteServersKey, resolved.toLowerCase(Locale.ROOT));
        notice(p, "已取消屏蔽 " + plugin.prettyName(resolved) + "。");
    }

    public void unignoreAll(Player p) {
        p.getPersistentDataContainer().remove(mutePlayersKey);
        p.getPersistentDataContainer().remove(muteServersKey);
        notice(p, "已清空全部互通屏蔽。");
    }

    public void listIgnores(Player p) {
        List<String> pl = csv(p, mutePlayersKey);
        List<String> sv = csv(p, muteServersKey);
        if (pl.isEmpty() && sv.isEmpty()) {
            notice(p, "没有屏蔽任何人。点外服消息的名字或 [屏蔽] 即可屏蔽。");
            return;
        }
        StringBuilder sb = new StringBuilder("当前屏蔽: ");
        if (!pl.isEmpty()) sb.append("玩家 ").append(String.join(", ", pl));
        if (!pl.isEmpty() && !sv.isEmpty()) sb.append(" | ");
        if (!sv.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (String c : sv) names.add(plugin.prettyName(c));
            sb.append("服 ").append(String.join(", ", names));
        }
        notice(p, sb.toString());
    }

    public void poll() {
        if (!plugin.getConfig().getBoolean("chat.enabled", true)) return;
        if (!plugin.store().ready()) return;
        try {
            if (lastId.get() < 0) {
                lastId.set(plugin.store().maxChatId());
                return;
            }
            var rows = plugin.store().chatAfter(lastId.get(), plugin.serverCode(), 40);
            if (!rows.isEmpty()) lastId.set(rows.get(rows.size() - 1).id());
            if (!rows.isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (var r : rows) broadcast(r);
                });
            }
            long now = System.currentTimeMillis();
            if (now - lastPrune > 60_000L) {
                lastPrune = now;
                plugin.store().pruneChat(30 * 60 * 1000L);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("互通聊天拉取失败: " + e.getMessage());
        }
    }

    private void broadcast(Models.ChatRow r) {
        String raw = r.message() == null ? "" : r.message();
        if (raw.startsWith(MSG)) {
            int cut = raw.indexOf('\u0001', MSG.length());
            if (cut < 0) return;
            String target = raw.substring(MSG.length(), cut);
            String text = raw.substring(cut + 1);
            Player dest = Bukkit.getPlayer(target);
            if (dest == null || !dest.isOnline()) return;
            deliverWhisper(dest, r.fromCode(), r.fromName(), r.playerName(), text);
            Bukkit.getConsoleSender().sendMessage("[ESLink MSG] " + r.playerName() + " -> " + target + ": " + strip(text));
            return;
        }
        String code = r.fromCode() == null ? "?" : r.fromCode();
        String pfx = plugin.getConfig().getString("chat.prefix", "[{name}] ");
        if (pfx == null) pfx = "[{name}] ";
        String shown = r.fromName();
        if (shown == null || shown.isBlank() || shown.equalsIgnoreCase(code)) shown = plugin.prettyName(code);
        pfx = pfx.replace("{code}", code).replace("{name}", shown);
        String dye = ColorUtil.dye(plugin.serverColorOf(code));
        pfx = "&" + dye + pfx;
        String body = keepColor(raw);
        ItemStack decoded = null;
        if (plugin.getConfig().getBoolean("chat.item", true) && ItemChat.hasToken(ChatColor.stripColor(body))
                && r.itemKey() != null && !r.itemKey().isBlank()) {
            decoded = ItemCodec.tryDecode(r.itemBlob(), r.itemKey(), Math.max(1, r.itemAmount()));
        }
        String pname = r.playerName() == null ? "?" : r.playerName();
        String pfxShow = pfx.endsWith(" ") ? pfx.substring(0, pfx.length() - 1) : pfx;
        var pfxComp = ChatMsg.click(pfxShow, "/link ignore server " + code, "点击屏蔽 " + plugin.prettyName(code) + " 的互通消息");
        var nameComp = ChatMsg.click("&" + dye + pname, "/link ignore player " + pname, "点击屏蔽 " + pname);
        var muteBtn = ChatMsg.click("&8 [屏蔽]", "/link ignore player " + pname, "屏蔽此人的互通消息");
        var replyBtn = ChatMsg.suggest("&8 [回]", "/link msg " + pname + " ", "私聊 " + pname);
        var msg = ChatMsg.itemBody(body, decoded, r.itemName());
        String console = strip(ColorUtil.colorize(pfx)) + " " + pname + ": " + strip(ColorUtil.colorize(body));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (mutedServer(p, code) || mutedPlayer(p, pname)) continue;
            ChatMsg.send(p, pfxComp, ChatMsg.text(" "), nameComp, ChatMsg.text(": "), msg, muteBtn, replyBtn);
        }
        Bukkit.getConsoleSender().sendMessage(console);
    }

    private void deliverWhisper(Player dest, String fromCode, String fromName, String fromPlayer, String text) {
        if (dest == null || !dest.isOnline()) return;
        if (mutedServer(dest, fromCode) || mutedPlayer(dest, fromPlayer)) return;
        String shown = fromName == null || fromName.isBlank() ? plugin.prettyName(fromCode) : fromName;
        String who = fromPlayer == null ? "?" : fromPlayer;
        ChatMsg.send(dest,
                ChatMsg.legacy("&d[私聊] &7[" + shown + "] &e" + who + "&f: " + text),
                ChatMsg.suggest("&8 [回]", "/link msg " + who + " ", "回复 " + who));
    }

    private List<String> csv(Player p, NamespacedKey k) {
        String v = p.getPersistentDataContainer().get(k, PersistentDataType.STRING);
        List<String> out = new ArrayList<>();
        if (v == null || v.isBlank()) return out;
        for (String s : v.split(",")) {
            if (!s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    private void addCsv(Player p, NamespacedKey k, String v) {
        List<String> cur = csv(p, k);
        if (!cur.contains(v)) cur.add(v);
        p.getPersistentDataContainer().set(k, PersistentDataType.STRING, String.join(",", cur));
    }

    private void removeCsv(Player p, NamespacedKey k, String v) {
        List<String> cur = csv(p, k);
        cur.removeIf(s -> s.equalsIgnoreCase(v));
        if (cur.isEmpty()) p.getPersistentDataContainer().remove(k);
        else p.getPersistentDataContainer().set(k, PersistentDataType.STRING, String.join(",", cur));
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replaceAll("[^a-zA-Z0-9_\\u4e00-\\u9fff]", "");
    }

    public String localTag() {
        String dye = ColorUtil.dye(plugin.serverColor());
        return ColorUtil.colorize("&" + dye + "[互通] ");
    }

    static String keepColor(String s) {
        if (s == null) return "";
        return s.replace('\n', ' ').replace('\r', ' ').trim();
    }

    static String strip(String s) {
        if (s == null) return "";
        s = ChatColor.stripColor(s);
        s = s.replaceAll("(?i)[&§]x(([&§][0-9a-f]){6})", "");
        s = s.replaceAll("(?i)[&§]#([0-9a-f]{6})", "");
        s = s.replaceAll("(?i)[&§][0-9a-fk-or]", "");
        return s.trim();
    }
}
