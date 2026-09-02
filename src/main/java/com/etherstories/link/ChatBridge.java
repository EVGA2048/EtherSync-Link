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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ChatBridge {
    static final String MSG = "\u0001MSG\u0001";
    private final ESLinkPlugin plugin;
    private final NamespacedKey modeKey;
    private final NamespacedKey recvKey;
    private final NamespacedKey mutePlayersKey;
    private final NamespacedKey muteServersKey;
    private final AtomicLong lastId = new AtomicLong(-1);
    private long lastPrune;
    private final Map<UUID, Deque<Long>> sentAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRemind = new ConcurrentHashMap<>();
    private final Map<UUID, Emit> pending = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastSentBody = new ConcurrentHashMap<>();
    /** 离线网页跨服开关；人在线时以 PDC 为准 */
    private final Map<UUID, Boolean> webAll = new ConcurrentHashMap<>();

    static final int PRI_ARCLIGHT = 0;
    static final int PRI_LEGACY = 1;
    static final int PRI_PAPER = 2;

    private record Emit(String msg, ItemStack item, int pri) {}

    public ChatBridge(ESLinkPlugin plugin) {
        this.plugin = plugin;
        this.modeKey = new NamespacedKey(plugin, "chat");
        this.recvKey = new NamespacedKey(plugin, "chat_recv");
        this.mutePlayersKey = new NamespacedKey(plugin, "mute_p");
        this.muteServersKey = new NamespacedKey(plugin, "mute_s");
    }

    public void resetCursor() {
        lastId.set(-1);
    }

    public void notice(Player p, String text) {
        ChatMsg.notice(p, text);
    }

    public boolean defaultAll() {
        return "all".equalsIgnoreCase(plugin.getConfig().getString("chat.default", "local"));
    }

    public boolean isAll(Player p) {
        String v = p.getPersistentDataContainer().get(modeKey, PersistentDataType.STRING);
        if (v == null || v.isBlank()) return defaultAll();
        return "all".equalsIgnoreCase(v);
    }

    public boolean isAll(UUID uuid) {
        if (uuid == null) return defaultAll();
        Player p = Bukkit.getPlayer(uuid);
        if (p != null && p.isOnline()) return isAll(p);
        return webAll.getOrDefault(uuid, defaultAll());
    }

    public void setAll(Player p, boolean all) {
        setAll(p, all, true);
    }

    public void setAll(Player p, boolean all, boolean notice) {
        webAll.put(p.getUniqueId(), all);
        p.getPersistentDataContainer().set(modeKey, PersistentDataType.STRING, all ? "all" : "local");
        if (!notice) return;
        if (all) {
            notice(p, "发言将发往全部互通服。过快发言不会传到其他服务器。可在大厅勾选要接收的服务器。");
        } else {
            notice(p, "发言仅本服可见，不会发往其他服务器。");
        }
    }

    public void setAll(UUID uuid, boolean all) {
        if (uuid == null) return;
        webAll.put(uuid, all);
        Player p = Bukkit.getPlayer(uuid);
        if (p != null && p.isOnline()) setAll(p, all, false);
    }

    /** 网页离线拨过的开关，进服写回 PDC，不弹提示。 */
    public void applyWebAll(Player p) {
        if (p == null) return;
        Boolean pending = webAll.get(p.getUniqueId());
        if (pending == null) return;
        p.getPersistentDataContainer().set(modeKey, PersistentDataType.STRING, pending ? "all" : "local");
    }

    public void toggle(Player p) {
        setAll(p, !isAll(p));
    }

    public enum Recv { LOCAL, ALL, LIST }

    public Recv recvMode(Player p) {
        String v = p.getPersistentDataContainer().get(recvKey, PersistentDataType.STRING);
        if (v == null || v.isBlank()) {
            return "all".equalsIgnoreCase(plugin.getConfig().getString("chat.default", "local"))
                    ? Recv.ALL : Recv.LOCAL;
        }
        if ("all".equalsIgnoreCase(v)) return Recv.ALL;
        if ("local".equalsIgnoreCase(v) || "none".equalsIgnoreCase(v)) return Recv.LOCAL;
        return Recv.LIST;
    }

    public Set<String> recvServers(Player p) {
        Set<String> out = new HashSet<>();
        String v = p.getPersistentDataContainer().get(recvKey, PersistentDataType.STRING);
        if (v == null || v.isBlank() || "all".equalsIgnoreCase(v)
                || "local".equalsIgnoreCase(v) || "none".equalsIgnoreCase(v)) {
            return out;
        }
        for (String s : v.split(",")) {
            if (!s.isBlank()) out.add(s.trim().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    public boolean receives(Player p, String fromCode) {
        if (fromCode == null) return false;
        Recv m = recvMode(p);
        if (m == Recv.LOCAL) return false;
        if (m == Recv.ALL) return true;
        return recvServers(p).contains(fromCode.toLowerCase(Locale.ROOT));
    }

    public void setRecvAll(Player p) {
        p.getPersistentDataContainer().set(recvKey, PersistentDataType.STRING, "all");
        notice(p, "将接收全部互通服的消息。");
    }

    public void setRecvLocal(Player p) {
        p.getPersistentDataContainer().set(recvKey, PersistentDataType.STRING, "local");
        notice(p, "不再接收其他服务器的互通消息。");
    }

    public void toggleRecvServer(Player p, String code) {
        String resolved = plugin.resolveServerCode(code);
        if (resolved.isEmpty()) {
            notice(p, "未找到这台服务器。");
            return;
        }
        String key = resolved.toLowerCase(Locale.ROOT);
        Recv mode = recvMode(p);
        Set<String> set = recvServers(p);
        if (mode == Recv.ALL) {
            for (Models.ServerRow s : plugin.cachedServers()) {
                if (s.code() != null && !s.code().equalsIgnoreCase(plugin.serverCode())) {
                    set.add(s.code().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (set.contains(key)) set.remove(key);
        else set.add(key);
        if (set.isEmpty()) {
            setRecvLocal(p);
            return;
        }
        p.getPersistentDataContainer().set(recvKey, PersistentDataType.STRING, String.join(",", set));
        boolean on = set.contains(key);
        notice(p, on
                ? "已接收来自「" + plugin.prettyName(resolved) + "」的消息。"
                : "已停止接收「" + plugin.prettyName(resolved) + "」的消息。");
    }

    public String recvLabel(Player p) {
        return switch (recvMode(p)) {
            case ALL -> "全部互通服";
            case LOCAL -> "不接收外服";
            case LIST -> "已选 " + recvServers(p).size() + " 台";
        };
    }

    public void whisper(Player p, String target, String raw) {
        if (!plugin.getConfig().getBoolean("chat.enabled", true)) {
            notice(p, "互通聊天已关闭。");
            return;
        }
        if (!plugin.store().ready()) {
            notice(p, "数据库未连接。");
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
            notice(p, "不能向自己发送私聊。");
            return;
        }
        if (tooFast(p)) {
            remindFast(p);
            return;
        }
        String text = msg;
        String wire = MSG + name + "\u0001" + text;
        ChatMsg.tell(p, "&bESLink &7» &d→ " + name + "&f: " + text);
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

    /**
     * Paper 与 Bukkit 聊天事件同一句会各来一次。Paper 签名原文优先；
     * 同 tick 合并，避免 Youer 取消原包后 getMessage() 卡在上一句。
     */
    public void emitFromChat(Player p, String raw, ItemStack item, int priority) {
        if (p == null || !plugin.getConfig().getBoolean("chat.enabled", true) || !isAll(p)) return;
        String msg = keepColor(raw);
        if (msg.isBlank()) return;
        ItemStack hand = (item == null || item.getType().isAir()) ? null : item.clone();
        pending.compute(p.getUniqueId(), (k, cur) -> {
            String last = lastSentBody.get(k);
            boolean incomingFresh = last == null || !msg.equals(last);
            if (cur != null) {
                boolean curFresh = last == null || !cur.msg().equals(last);
                if (incomingFresh && !curFresh) return new Emit(msg, hand, priority);
                if (curFresh && !incomingFresh) return cur;
                if (cur.pri() > priority) return cur;
            }
            return new Emit(msg, hand, priority);
        });
        UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> flushEmit(id));
    }

    private void flushEmit(UUID id) {
        Emit e = pending.remove(id);
        if (e == null) return;
        Player p = Bukkit.getPlayer(id);
        if (p == null || !p.isOnline()) return;
        lastSentBody.put(id, e.msg());
        showLocal(p, e.msg(), e.item());
        send(p, e.msg(), e.item());
    }

    /** 本服显示互通聊天。必须走系统消息，不能改玩家聊天包，否则签名失败全员掉线。 */
    public void showLocal(Player from, String raw, ItemStack item) {
        if (from == null || !from.isOnline()) return;
        String body = keepColor(raw);
        if (body.isBlank()) return;
        if (plugin.getConfig().getBoolean("chat.item", true) && ItemChat.hasToken(body)) {
            body = ItemChat.replacePlain(body, item, null);
        }
        String line = localTag() + from.getName() + ": " + body;
        String console = strip(line);
        for (Player p : Bukkit.getOnlinePlayers()) ChatMsg.tell(p, line);
        Bukkit.getConsoleSender().sendMessage(console);
        LinkChatShowEvent.call(from.getName(), strip(body), line, "link");
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

    /** 网页发言：不经过游戏聊天包，直接写入互通库。人在线则走 send。 */
    public void emitFromWeb(UUID uuid, String name, String raw) {
        if (uuid == null || name == null || name.isBlank()) return;
        if (!isAll(uuid)) return;
        Player p = Bukkit.getPlayer(uuid);
        if (p != null && p.isOnline()) {
            send(p, raw, null);
            return;
        }
        if (!plugin.getConfig().getBoolean("chat.enabled", true)) return;
        if (!plugin.store().ready()) return;
        String msg = keepColor(raw);
        int max = Math.max(8, plugin.getConfig().getInt("chat.max-length", 128));
        if (msg.length() > max) msg = msg.substring(0, max);
        if (msg.isBlank()) return;
        final String text = msg;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (plugin.store().banned(plugin.serverCode(), uuid)) return;
                plugin.store().insertChat(plugin.serverCode(), plugin.serverName(),
                        uuid, name, text, null, null, null, null);
            } catch (Exception e) {
                plugin.getLogger().warning("网页互通聊天发送失败: " + e.getMessage());
            }
        });
    }

    private boolean tooFast(Player p) {
        long now = System.currentTimeMillis();
        long window = Math.max(2, plugin.getConfig().getLong("chat.fast-window-seconds", 6)) * 1000L;
        int max = Math.max(2, plugin.getConfig().getInt("chat.fast-count", 12));
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
            notice(p, "发言过快，本条未发往其他服务器。大厅可将聊天改回仅本服。");
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
        if (list.contains(plugin.serverShortOf(code).toLowerCase(Locale.ROOT))) return true;
        return list.contains(plugin.prettyName(code).toLowerCase(Locale.ROOT));
    }

    public void ignorePlayer(Player p, String name) {
        name = sanitize(name);
        if (name.isEmpty()) {
            notice(p, "用法: /link ignore player 玩家名");
            return;
        }
        addCsv(p, mutePlayersKey, name.toLowerCase(Locale.ROOT));
        notice(p, "已屏蔽 " + name + " 的互通消息。可用 /link unignore player " + name + " 取消。");
    }

    public void ignoreServer(Player p, String code) {
        String resolved = plugin.resolveServerCode(code);
        if (resolved.isEmpty()) {
            notice(p, "用法: /link ignore server 服务器名");
            return;
        }
        addCsv(p, muteServersKey, resolved.toLowerCase(Locale.ROOT));
        notice(p, "已屏蔽来自「" + plugin.prettyName(resolved) + "」的互通消息。可用 /link unignore server " + resolved + " 取消。");
    }

    public void unignorePlayer(Player p, String name) {
        name = sanitize(name);
        removeCsv(p, mutePlayersKey, name.toLowerCase(Locale.ROOT));
        notice(p, "已取消对 " + name + " 的屏蔽。");
    }

    public void unignoreServer(Player p, String code) {
        String resolved = plugin.resolveServerCode(code);
        removeCsv(p, muteServersKey, resolved.toLowerCase(Locale.ROOT));
        notice(p, "已取消对「" + plugin.prettyName(resolved) + "」的屏蔽。");
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
            notice(p, "当前没有屏蔽。可在大厅聊天页勾选要接收的服务器。");
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
        String pfx = plugin.getConfig().getString("chat.prefix", "[{short}] ");
        if (pfx == null) pfx = "[{short}] ";
        String shown = plugin.prettyName(code);
        String tag = plugin.serverShortOf(code);
        pfx = pfx.replace("{code}", code).replace("{short}", tag).replace("{name}", shown);
        String dye = ColorUtil.dye(plugin.serverColorOf(code));
        pfx = "&" + dye + pfx;
        String body = keepColor(raw);
        ItemStack decoded = null;
        if (plugin.getConfig().getBoolean("chat.item", true) && ItemChat.hasToken(ChatColor.stripColor(body))
                && r.itemKey() != null && !r.itemKey().isBlank()) {
            decoded = ItemCodec.tryDecode(r.itemBlob(), r.itemKey(), Math.max(1, r.itemAmount()));
        }
        String pname = r.playerName() == null ? "?" : r.playerName();
        if (plugin.getConfig().getBoolean("chat.item", true) && ItemChat.hasToken(ChatColor.stripColor(body))) {
            body = ItemChat.replacePlain(body, decoded, r.itemName());
        }
        String line = pfx + pname + ": " + body;
        String display = ColorUtil.safe(line);
        String console = strip(display);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (mutedServer(p, code) || mutedPlayer(p, pname)) continue;
            if (!receives(p, code)) continue;
            ChatMsg.tell(p, line);
        }
        Bukkit.getConsoleSender().sendMessage(console);
        LinkChatShowEvent.call(pname, strip(body), display, "link");
    }

    private void deliverWhisper(Player dest, String fromCode, String fromName, String fromPlayer, String text) {
        if (dest == null || !dest.isOnline()) return;
        if (mutedServer(dest, fromCode) || mutedPlayer(dest, fromPlayer)) return;
        String shown = plugin.serverShortOf(fromCode);
        String who = fromPlayer == null ? "?" : fromPlayer;
        ChatMsg.tell(dest, "&d[私聊] &7[" + shown + "] &e" + who + "&f: " + text);
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
        String tag = plugin.serverShort();
        return ColorUtil.colorize("&" + dye + "[" + tag + "] ");
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
