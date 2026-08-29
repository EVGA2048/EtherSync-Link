package com.etherstories.link;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ESLinkPlugin extends JavaPlugin {

    private Store store;
    private VaultHook vault;
    private Sessions sessions;
    private LinkGui gui;
    private ChestNet chests;
    private IoNet io;
    private ChatBridge chat;
    private AlertNet alerts;
    private MarketNet markets;
    private final Set<String> rxKeys = ConcurrentHashMap.newKeySet();
    private final Set<java.util.UUID> guideWelcomed = ConcurrentHashMap.newKeySet();
    private final java.util.Map<String, Models.ServerRow> serverCache = new ConcurrentHashMap<>();
    private volatile boolean ioEnabled = true;
    private volatile boolean transportEnabled = true;
    private final Set<String> blockedComponents = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> selfBuyAt = new ConcurrentHashMap<>();
    private final Map<UUID, ClaimGate> claimGates = new ConcurrentHashMap<>();
    private NamespacedKey selfBuyDayKey;
    private NamespacedKey selfBuyCountKey;
    private NamespacedKey claimMissKey;
    private NamespacedKey claimMissLockKey;
    private NamespacedKey claimHeadLockKey;
    private volatile String marketRateMode = "";

    @Override
    public void onEnable() {
        LinkCommand exec = new LinkCommand(this);
        var cmd = getCommand("link");
        if (cmd != null) {
            cmd.setExecutor(exec);
            cmd.setTabCompleter(exec);
            cmd.setUsage("/link");
        } else {
            getLogger().severe("plugin.yml 没有 link 指令，检查是否用错了 jar");
        }
        LinkLog.attach(this);

        try {
            RuntimeEnv.probe();
            getLogger().info("平台: " + RuntimeEnv.label() + " · " + RuntimeEnv.itemStrategy());
            saveDefaultConfig();
            ConfigUpdater.migrate(this);
            ensureCore();
            loadTransportState();
            vault.hook();
            ContainerSupport.configure(getConfig().getString("chest.containers", "auto"));
            ContainerSupport.scheduleProbe(this);

            Bukkit.getPluginManager().registerEvents(new GuiListener(this), this);
            ChatTap tap = new ChatTap(this);
            Bukkit.getPluginManager().registerEvents(tap, this);
            tap.hookOnline();
            registerChatListener();
            Bukkit.getPluginManager().registerEvents(new ChestListener(this), this);
            Bukkit.getPluginManager().registerEvents(new ProxyLockListener(), this);
            Bukkit.getPluginManager().registerEvents(new IoListener(this), this);

            if (!store.connect()) {
                getLogger().warning("MySQL 未连上。填 plugins/ESLink/config.yml 后 /link reload");
            } else {
                getLogger().info("ESLink 已连接  " + serverCode() + " / " + serverName());
            }
            startTasks();
        } catch (Throwable t) {
            getLogger().severe("ESLink 启动出错（指令仍可用）: " + t.getMessage());
            t.printStackTrace();
            try { ensureCore(); } catch (Throwable ignored) {}
        }
    }

    /** 保证大厅/聊天/箱子对象存在。reload 或启动中途失败后必须再调一次。 */
    public void ensureCore() {
        if (store == null) store = new Store(this);
        if (vault == null) vault = new VaultHook();
        if (sessions == null) sessions = new Sessions();
        if (gui == null) gui = new LinkGui(this);
        if (chests == null) chests = new ChestNet(this);
        if (io == null) io = new IoNet(this);
        if (chat == null) chat = new ChatBridge(this);
        if (alerts == null) alerts = new AlertNet(this);
        if (markets == null) markets = new MarketNet(this);
        else markets.reload();
        if (selfBuyDayKey == null) selfBuyDayKey = new NamespacedKey(this, "sb_day");
        if (selfBuyCountKey == null) selfBuyCountKey = new NamespacedKey(this, "sb_n");
        if (claimMissKey == null) claimMissKey = new NamespacedKey(this, "cm_n");
        if (claimMissLockKey == null) claimMissLockKey = new NamespacedKey(this, "cm_lock");
        if (claimHeadLockKey == null) claimHeadLockKey = new NamespacedKey(this, "cm_head");
    }

    /** 从 config 加载急停开关与组件黑名单（重启/重载后仍生效）。 */
    private void loadTransportState() {
        transportEnabled = getConfig().getBoolean("transport.enabled", true);
        blockedComponents.clear();
        for (String s : getConfig().getStringList("blocked-components")) {
            if (s != null && !s.isBlank()) blockedComponents.add(s.trim().toLowerCase(Locale.ROOT));
        }
    }

    public boolean transportEnabled() { return transportEnabled; }

    public void setTransportEnabled(boolean v) {
        transportEnabled = v;
        getConfig().set("transport.enabled", v);
        saveConfig();
    }

    public boolean componentBlocked(String id) {
        if (id == null || id.isBlank()) return false;
        return blockedComponents.contains(id.trim().toLowerCase(Locale.ROOT));
    }

    public java.util.Set<String> blockedComponentIds() {
        return Set.copyOf(blockedComponents);
    }

    public void blockComponent(String id) {
        if (id == null || id.isBlank()) return;
        blockedComponents.add(id.trim().toLowerCase(Locale.ROOT));
        getConfig().set("blocked-components", new java.util.ArrayList<>(blockedComponents));
        saveConfig();
    }

    public void unblockComponent(String id) {
        if (id == null) return;
        blockedComponents.remove(id.trim().toLowerCase(Locale.ROOT));
        getConfig().set("blocked-components", new java.util.ArrayList<>(blockedComponents));
        saveConfig();
    }

    public int chestBatchDelaySeconds() {
        return Math.max(0, Math.min(60, getConfig().getInt("chest.batch-delay-seconds", 3)));
    }

    private void registerChatListener() {
        boolean paper = false;
        try {
            Class.forName("io.papermc.paper.event.player.AsyncChatEvent");
            Bukkit.getPluginManager().registerEvents(new ChatListener(this), this);
            paper = true;
        } catch (Throwable ignored) {
        }
        Bukkit.getPluginManager().registerEvents(new ChatListenerLegacy(this), this);
        if (paper && RuntimeEnv.kind() == RuntimeEnv.Kind.ARCLIGHT) {
            getLogger().info("聊天: Arclight 不取消原包（避免签名会话卡住）");
        } else if (paper && RuntimeEnv.hybrid()) {
            getLogger().info("聊天: 签名原文 + 兼容去重（" + Bukkit.getName() + "）");
        } else if (!paper) {
            getLogger().info("聊天改用兼容模式（" + Bukkit.getName() + "）");
        }
    }

    @Override
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        LinkLog.detach();
        if (store != null) store.close();
    }

    public boolean reloadLink() {
        try {
            Bukkit.getScheduler().cancelTasks(this);
            reloadConfig();
            ConfigUpdater.migrate(this);
            ensureCore();
            loadTransportState();
            vault.hook();
            ContainerSupport.configure(getConfig().getString("chest.containers", "auto"));
            ContainerSupport.clearTrip();
            ContainerSupport.probe(this);
            if (store != null) store.close();
            store = new Store(this);
            if (markets != null) markets.reload();
            chat.resetCursor();
            if (alerts != null) alerts.resetCursor();
            boolean ok = store.connect();
            startTasks();
            return ok;
        } catch (Throwable t) {
            getLogger().severe("reload 失败: " + t.getMessage());
            t.printStackTrace();
            return false;
        }
    }

    private void startTasks() {
        ensureCore();
        long hb = Math.max(2, getConfig().getLong("heartbeat-seconds", 5)) * 20L;
        long scan = Math.max(1, getConfig().getLong("scan-seconds", 2)) * 20L;
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                if (store != null && store.ready()) {
                    store.heartbeat(serverCode(), serverName(), serverShort(), serverBlurb(), serverColor(), serverIcon(), linkRate());
                    rememberServers(store.servers());
                    Compat.publish(this);
                    Compat.refresh(this);
                }
                if (markets != null) markets.heartbeatAll();
            } catch (Exception e) {
                getLogger().warning("心跳失败: " + e.getMessage());
            }
        }, 10L, hb);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (chests != null) chests.tick();
        }, 40L, scan);
        ioEnabled = getConfig().getBoolean("io.enabled", true);
        if (!ioEnabled) getLogger().warning("互通红石已在 config.yml 里关闭（io.enabled: false）");
        if (ioEnabled) {
            long pulse = Math.max(1, getConfig().getLong("io.pulse-ticks", 2));
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                if (io != null) io.pulse();
            }, pulse, pulse);
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                if (io != null) io.poll();
            }, 20L, 10L);
        }
        long paint = Math.max(20, getConfig().getLong("io.paint-ticks", 40));
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (chests != null) chests.paint();
            if (ioEnabled && io != null) io.paint();
        }, paint, paint);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (chat != null) chat.poll();
            if (alerts != null) alerts.poll();
        }, 40L, scan);
        Bukkit.getScheduler().runTaskAsynchronously(this, this::refreshRxCache);
    }

    public void refreshRxCache() {
        if (store == null || !store.ready()) return;
        try {
            var rows = store.chestsOn(serverCode());
            Runnable apply = () -> {
                rxKeys.clear();
                for (var c : rows) {
                    if (!"RX".equals(c.role())) continue;
                    rxKeys.add(locKey(c.world(), c.x(), c.y(), c.z()));
                    org.bukkit.World w = Bukkit.getWorld(c.world());
                    if (w == null) continue;
                    Block o = ChestListener.otherHalf(w.getBlockAt(c.x(), c.y(), c.z()));
                    if (o != null) rxKeys.add(locKey(o.getWorld().getName(), o.getX(), o.getY(), o.getZ()));
                }
            };
            if (Bukkit.isPrimaryThread()) apply.run();
            else Bukkit.getScheduler().runTask(this, apply);
        } catch (Exception e) {
            getLogger().warning("刷新 RX 缓存失败: " + e.getMessage());
        }
    }

    public static String locKey(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }

    public boolean isRx(String world, int x, int y, int z) {
        return rxKeys.contains(locKey(world, x, y, z));
    }

    public void markRx(String world, int x, int y, int z, boolean on) {
        String k = locKey(world, x, y, z);
        if (on) rxKeys.add(k);
        else rxKeys.remove(k);
    }

    public void markRxNode(Block b, boolean on) {
        if (b == null) return;
        for (Block h : ChestListener.halves(b)) {
            markRx(h.getWorld().getName(), h.getX(), h.getY(), h.getZ(), on);
        }
    }

    public String serverCode() {
        String c = getConfig().getString("server.code", "ES2");
        return c == null ? "ES2" : c.trim().toUpperCase(Locale.ROOT);
    }

    public String serverName() {
        return prettyName(serverCode(), getConfig().getString("server.name", ""));
    }

    /** 聊天前缀用的短标签。空则用 code。 */
    public String serverShort() {
        return serverShortOf(serverCode(), getConfig().getString("server.short", ""));
    }

    public String serverShortOf(String code) {
        if (code != null && code.equalsIgnoreCase(serverCode())) return serverShort();
        if (code == null || code.isBlank()) return "?";
        Models.ServerRow s = serverCache.get(code.toUpperCase(Locale.ROOT));
        return serverShortOf(code, s == null ? null : s.shortName());
    }

    static String serverShortOf(String code, String shortName) {
        if (shortName != null) {
            String t = shortName.trim();
            if (!t.isEmpty()) return t;
        }
        return code == null || code.isBlank() ? "?" : code.trim();
    }

    public boolean guideWelcomed(Player p) {
        if (guideWelcomed.contains(p.getUniqueId())) return true;
        return GuideBook.seen(this, p);
    }

    public void markGuideWelcomed(Player p) {
        guideWelcomed.add(p.getUniqueId());
        try { GuideBook.markSeen(this, p); } catch (Throwable ignored) {}
    }

    public String serverBlurb() {
        String b = getConfig().getString("server.blurb", "");
        return b == null ? "" : b.trim();
    }

    public void rememberServers(List<Models.ServerRow> rows) {
        serverCache.clear();
        if (rows == null) return;
        for (Models.ServerRow s : rows) {
            if (s.code() != null) serverCache.put(s.code().toUpperCase(Locale.ROOT), s);
        }
    }

    public List<Models.ServerRow> cachedServers() {
        return new java.util.ArrayList<>(serverCache.values());
    }

    /** 玩家看见的服名。内部代号不展示。 */
    public String prettyName(String code) {
        if (code == null || code.isBlank()) return "?";
        Models.ServerRow s = serverCache.get(code.toUpperCase(Locale.ROOT));
        return prettyName(code, s == null ? null : s.name());
    }

    public static String prettyName(String code, String name) {
        if (name != null) {
            name = name.trim();
            if (!name.isEmpty() && (code == null || !name.equalsIgnoreCase(code))) return name;
        }
        if (code != null && code.equalsIgnoreCase("ES2")) return "以太物语";
        if (name != null && !name.isEmpty()) return name;
        return code == null || code.isBlank() ? "?" : code;
    }

    public String prettyBlurb(Models.ServerRow s) {
        if (s == null) return "";
        if (s.blurb() != null && !s.blurb().isBlank()) return s.blurb().trim();
        if (s.code() != null && s.code().equalsIgnoreCase(serverCode())) return serverBlurb();
        return "";
    }

    public String resolveServerCode(String input) {
        if (input == null) return "";
        String raw = input.trim();
        if (raw.isEmpty()) return "";
        for (Models.ServerRow s : serverCache.values()) {
            if (s.code() != null && s.code().equalsIgnoreCase(raw)) return s.code();
            if (s.name() != null && s.name().equalsIgnoreCase(raw)) return s.code();
            if (s.shortName() != null && s.shortName().equalsIgnoreCase(raw)) return s.code();
            if (prettyName(s.code(), s.name()).equalsIgnoreCase(raw)) return s.code();
        }
        if (raw.equals("以太物语") || raw.equalsIgnoreCase("ES2")) return "ES2";
        return raw.toUpperCase(Locale.ROOT);
    }

    public static String roleCn(String role) {
        if ("RX".equalsIgnoreCase(role)) return "接收";
        if ("TX".equalsIgnoreCase(role)) return "发送";
        if ("BK".equalsIgnoreCase(role)) return "回退";
        return role == null ? "?" : role;
    }

    public int chestStacksPerScan() {
        return Math.max(1, Math.min(27, getConfig().getInt("chest.stacks-per-scan", 4)));
    }

    public int chestQueueLimit() {
        return Math.max(1, Math.min(64, getConfig().getInt("chest.queue-limit", 16)));
    }

    public int chestHeavyDelaySeconds() {
        return Math.max(0, Math.min(30, getConfig().getInt("chest.heavy-delay-seconds", 3)));
    }

    public int chestHeavyMaxSeconds() {
        int base = chestHeavyDelaySeconds();
        return Math.max(base, Math.min(60, getConfig().getInt("chest.heavy-max-seconds", 8)));
    }

    public long offlineMs() {
        return Math.max(10, getConfig().getLong("offline-after-seconds", 20)) * 1000L;
    }

    public boolean serverLive(String code) {
        if (code == null || code.isBlank()) return false;
        if (code.equalsIgnoreCase(serverCode())) return true;
        Models.ServerRow s = serverCache.get(code.toUpperCase(Locale.ROOT));
        return s != null && s.online(offlineMs());
    }

    public long ioKeepaliveMs() {
        return Math.max(250, getConfig().getLong("io.keepalive-ms", 1000));
    }

    public long ioStaleMs() {
        return Math.max(ioKeepaliveMs() * 2, getConfig().getLong("io.stale-ms", 5000));
    }

    public boolean allowed(org.bukkit.inventory.ItemStack item) {
        if (!ItemKeys.real(item)) return false;
        if (Items.hopperLocked(this, item)) return false;
        List<String> wl = getConfig().getStringList("whitelist");
        if (wl == null || wl.isEmpty()) return true;
        String key = Items.itemKey(item);
        String shortKey = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
        for (String w : wl) {
            if (w == null) continue;
            String t = w.trim();
            if (t.equalsIgnoreCase(key) || t.equalsIgnoreCase(shortKey)) return true;
        }
        return false;
    }

    public void msg(Player p, String s) {
        p.sendMessage(ColorUtil.colorize("&bESLink &7» &f" + s));
    }

    public Store store() { return store; }
    public VaultHook vault() {
        if (vault == null) vault = new VaultHook();
        return vault;
    }
    public Sessions sessions() {
        if (sessions == null) sessions = new Sessions();
        return sessions;
    }
    public LinkGui gui() {
        if (gui == null) gui = new LinkGui(this);
        return gui;
    }
    public ChestNet chests() {
        if (chests == null) chests = new ChestNet(this);
        return chests;
    }
    public boolean ioEnabled() {
        return ioEnabled;
    }

    public IoNet io() {
        if (io == null) io = new IoNet(this);
        return io;
    }
    public ChatBridge chat() {
        if (chat == null) chat = new ChatBridge(this);
        return chat;
    }
    public AlertNet alerts() {
        if (alerts == null) alerts = new AlertNet(this);
        return alerts;
    }
    public MarketNet markets() {
        if (markets == null) markets = new MarketNet(this);
        return markets;
    }

    public String serverColor() {
        String c = getConfig().getString("server.color", "LIGHT_BLUE");
        return c == null || c.isBlank() ? "LIGHT_BLUE" : c.trim().toUpperCase(Locale.ROOT);
    }

    public String serverColorOf(String code) {
        if (code != null && code.equalsIgnoreCase(serverCode())) return serverColor();
        if (code == null) return "LIGHT_BLUE";
        Models.ServerRow s = serverCache.get(code.toUpperCase(Locale.ROOT));
        if (s == null || s.color() == null || s.color().isBlank()) return "LIGHT_BLUE";
        return s.color().trim().toUpperCase(Locale.ROOT);
    }

    public String serverIcon() {
        String i = getConfig().getString("server.icon", "TERRACOTTA");
        return i == null || i.isBlank() ? "TERRACOTTA" : i.trim().toUpperCase(Locale.ROOT);
    }

    public void setServerColor(String color) {
        getConfig().set("server.color", color);
        saveConfig();
    }

    public void setServerIcon(String icon) {
        getConfig().set("server.icon", icon);
        saveConfig();
    }

    public boolean alertLocalListing() { return getConfig().getBoolean("alerts.listing", true); }
    public boolean alertRemoteListing() { return getConfig().getBoolean("alerts.listing-remote", true); }
    public boolean alertChestAdmin() { return getConfig().getBoolean("alerts.chest-admin", true); }

    public void toggleCfg(String path) {
        getConfig().set(path, !getConfig().getBoolean(path, true));
        saveConfig();
    }

    public boolean tradeEnabled() { return getConfig().getBoolean("trade.enabled", true); }

    public boolean walletEnabled() { return getConfig().getBoolean("trade.wallet", true); }

    public boolean claimCodeEnabled() { return getConfig().getBoolean("trade.claim-code", true); }

    public void setClaimCodeEnabled(boolean v) {
        getConfig().set("trade.claim-code", v);
        saveConfig();
    }

    public String pickupLocked(Player p) {
        long now = System.currentTimeMillis();
        var pdc = p.getPersistentDataContainer();
        long head = pdc.getOrDefault(claimHeadLockKey, PersistentDataType.LONG, 0L);
        if (now < head) {
            long sec = Math.max(1, (head - now) / 1000L);
            return "取件已暂停（点错货主）。请 " + sec + " 秒后再试。";
        }
        long miss = pdc.getOrDefault(claimMissLockKey, PersistentDataType.LONG, 0L);
        if (now < miss) {
            long sec = Math.max(1, (miss - now) / 1000L);
            return "取件已暂停（多次输入无效取件码）。请 " + sec + " 秒后再试。";
        }
        return null;
    }

    public void pickupMiss(Player p) {
        var pdc = p.getPersistentDataContainer();
        int n = pdc.getOrDefault(claimMissKey, PersistentDataType.INTEGER, 0) + 1;
        int limit = Math.max(2, getConfig().getInt("trade.claim-miss-limit", 5));
        pdc.set(claimMissKey, PersistentDataType.INTEGER, n);
        if (n >= limit) {
            int min = Math.max(1, getConfig().getInt("trade.claim-miss-lock-minutes", 30));
            pdc.set(claimMissLockKey, PersistentDataType.LONG, System.currentTimeMillis() + min * 60_000L);
            pdc.set(claimMissKey, PersistentDataType.INTEGER, 0);
            pingAdmins("&c" + p.getName() + " 连续输入不存在的取件码 " + limit + " 次，已锁定 " + min + " 分钟。");
            pluginMsgLock(p, "无效取件码次数过多，取件已暂停 " + min + " 分钟。");
        }
    }

    public void pickupMissReset(Player p) {
        p.getPersistentDataContainer().set(claimMissKey, PersistentDataType.INTEGER, 0);
    }

    public void pickupHeadFail(Player p) {
        int min = Math.max(1, getConfig().getInt("trade.claim-head-lock-minutes", 60));
        p.getPersistentDataContainer().set(claimHeadLockKey, PersistentDataType.LONG,
                System.currentTimeMillis() + min * 60_000L);
        pingAdmins("&c" + p.getName() + " 取件时点错货主，已锁定 " + min + " 分钟。");
    }

    public void pingAdmins(String colored) {
        String line = ColorUtil.colorize("&bESLink &7» &f" + colored);
        for (Player a : Bukkit.getOnlinePlayers()) {
            if (a.hasPermission("eslink.admin")) a.sendMessage(line);
        }
        getLogger().info(ColorUtil.colorize(colored).replaceAll("§.", ""));
    }

    private void pluginMsgLock(Player p, String s) {
        msg(p, "&c" + s);
    }

    public String claimLocked(Player p) {
        ClaimGate g = claimGates.get(p.getUniqueId());
        if (g == null) return null;
        long now = System.currentTimeMillis();
        if (now < g.lockUntil) {
            long sec = Math.max(1, (g.lockUntil - now) / 1000L);
            return "试错次数过多，请 " + sec + " 秒后再试。";
        }
        return null;
    }

    public void claimFail(Player p) {
        ClaimGate g = claimGates.computeIfAbsent(p.getUniqueId(), u -> new ClaimGate());
        g.fails++;
        if (g.fails >= 8) {
            g.lockUntil = System.currentTimeMillis() + 15 * 60_000L;
            g.fails = 0;
        }
    }

    public void claimOk(Player p) {
        claimGates.remove(p.getUniqueId());
    }

    private static final class ClaimGate {
        int fails;
        long lockUntil;
    }

    public void setWalletEnabled(boolean v) {
        getConfig().set("trade.wallet", v);
        saveConfig();
    }

    public double taxRate() {
        double r = getConfig().getDouble("trade.tax-rate", 0);
        if (r < 0) return 0;
        if (r > 1) return 1;
        return r;
    }

    public double taxOf(double price) {
        if (!tradeEnabled() || price <= 0) return 0;
        return roundMoney(price * taxRate());
    }

    /** 1 本服货币 = link-rate 互通货币。1:1.5 填 1.5。 */
    public double linkRate() {
        double r = getConfig().getDouble("trade.link-rate", 1);
        if (r <= 0) return 1;
        if (r > 1000) return 1000;
        return roundMoney(r);
    }

    public void setLinkRate(double rate) {
        if (rate < 0.01) rate = 0.01;
        if (rate > 1000) rate = 1000;
        getConfig().set("trade.link-rate", roundMoney(rate));
        saveConfig();
        marketRateMode = "manual";
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                if (store != null && store.ready()) {
                    store.heartbeat(serverCode(), serverName(), serverShort(), serverBlurb(),
                            serverColor(), serverIcon(), linkRate());
                }
                if (markets != null) markets.heartbeatAll(true, "manual");
            } catch (Exception ignored) {}
        });
    }

    public void setMarketRateFollow(boolean auto) {
        if (markets == null || !markets.httpEnabled()) {
            marketRateMode = "local";
            return;
        }
        marketRateMode = auto ? "auto" : "manual";
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                markets.heartbeatAll(!auto, auto ? "auto" : "manual");
            } catch (Exception ignored) {}
        });
    }

    public String marketRateMode() {
        if (marketRateMode == null || marketRateMode.isBlank()) {
            return (markets != null && markets.httpEnabled()) ? "auto" : "local";
        }
        return marketRateMode;
    }

    public boolean marketRateAuto() {
        return "auto".equalsIgnoreCase(marketRateMode());
    }

    public void onMarketRate(double rate, String mode) {
        if (mode != null && !mode.isBlank()) marketRateMode = mode;
        if (!"auto".equalsIgnoreCase(marketRateMode)) return;
        if (rate <= 0) return;
        double r = roundMoney(rate);
        if (Math.abs(r - linkRate()) < 0.005) return;
        getConfig().set("trade.link-rate", r);
        saveConfig();
    }

    public String linkRateText() {
        return "1:" + stripZeros(linkRate());
    }

    public double linkRateOf(String code) {
        if (code != null && code.equalsIgnoreCase(serverCode())) return linkRate();
        if (code == null) return 1;
        Models.ServerRow s = serverCache.get(code.toUpperCase(Locale.ROOT));
        if (s == null || s.linkRate() <= 0) return 1;
        return s.linkRate();
    }

    public String linkRateTextOf(String code) {
        return "1:" + stripZeros(linkRateOf(code));
    }

    public double toLink(double local) {
        return roundMoney(local * linkRate());
    }

    public double toLocal(double link) {
        return roundMoney(link / linkRate());
    }

    public String money(double v) {
        return vault().ok() ? vault().format(v) : stripZeros(v);
    }

    static double roundMoney(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    static String stripZeros(double v) {
        if (Math.abs(v - Math.round(v)) < 0.0001) return String.valueOf(Math.round(v));
        return String.format("%.2f", v);
    }

    static boolean isAllAmount(String msg) {
        if (msg == null) return false;
        String s = msg.trim();
        return s.equalsIgnoreCase("all") || s.equals("全部") || s.equalsIgnoreCase("max");
    }

    public boolean selfBuyEnabled() {
        return getConfig().getBoolean("trade.self-buy", true);
    }

    public double selfBuyMinFee() {
        return Math.max(0, getConfig().getDouble("trade.self-buy-min-fee", 64));
    }

    public double selfBuySurcharge() {
        double r = getConfig().getDouble("trade.self-buy-surcharge", 0);
        if (r < 0) return 0;
        if (r > 1) return 1;
        return r;
    }

    /** 跨服取回：货款不打给自己，只收 max(保底, 互通税 + 抽成)。参数为本服货币。 */
    public double selfBuyFee(double localPrice) {
        double tax = taxOf(localPrice);
        double extra = roundMoney(Math.max(0, localPrice) * selfBuySurcharge());
        return Math.max(selfBuyMinFee(), tax + extra);
    }

    public boolean selfListing(Models.Listing L, Player p) {
        return L != null && p != null && L.seller().equals(p.getUniqueId());
    }

    public boolean localListing(Models.Listing L) {
        return L != null && L.serverCode() != null
                && L.serverCode().equalsIgnoreCase(serverCode());
    }

    public String selfBuyGate(Player p) {
        if (!selfBuyEnabled()) return "本服未开放跨服取回。请到上架所在服务器下架。";
        int cd = Math.max(0, getConfig().getInt("trade.self-buy-cooldown-seconds", 20));
        if (cd > 0) {
            Long last = selfBuyAt.get(p.getUniqueId());
            long now = System.currentTimeMillis();
            if (last != null && now - last < cd * 1000L) {
                long left = (cd * 1000L - (now - last) + 999) / 1000;
                return "跨服取回正在冷却，请于 " + left + " 秒后再试。";
            }
        }
        int cap = getConfig().getInt("trade.self-buy-daily", 12);
        if (cap > 0) {
            int day = (int) (System.currentTimeMillis() / 86_400_000L);
            var pdc = p.getPersistentDataContainer();
            int stored = pdc.getOrDefault(selfBuyDayKey, PersistentDataType.INTEGER, -1);
            int n = stored == day ? pdc.getOrDefault(selfBuyCountKey, PersistentDataType.INTEGER, 0) : 0;
            if (n >= cap) return "今日跨服取回已达上限（" + cap + " 次）。";
        }
        return null;
    }

    public void selfBuyMark(Player p) {
        selfBuyAt.put(p.getUniqueId(), System.currentTimeMillis());
        int cap = getConfig().getInt("trade.self-buy-daily", 12);
        if (cap <= 0) return;
        int day = (int) (System.currentTimeMillis() / 86_400_000L);
        var pdc = p.getPersistentDataContainer();
        int stored = pdc.getOrDefault(selfBuyDayKey, PersistentDataType.INTEGER, -1);
        int n = stored == day ? pdc.getOrDefault(selfBuyCountKey, PersistentDataType.INTEGER, 0) : 0;
        pdc.set(selfBuyDayKey, PersistentDataType.INTEGER, day);
        pdc.set(selfBuyCountKey, PersistentDataType.INTEGER, n + 1);
    }

    public void setTradeEnabled(boolean v) {
        getConfig().set("trade.enabled", v);
        saveConfig();
    }

    public void setTaxRate(double rate) {
        if (rate < 0) rate = 0;
        if (rate > 1) rate = 1;
        getConfig().set("trade.tax-rate", Math.round(rate * 100.0) / 100.0);
        saveConfig();
    }

    public String taxRateText() {
        return String.format("%.0f%%", taxRate() * 100.0);
    }

    public void depositTax(double tax) {
        if (tax <= 0 || vault == null || !vault.ok()) return;
        String sink = getConfig().getString("trade.sink-account", "");
        if (sink == null || sink.isBlank()) {
            var uni = Bukkit.getPluginManager().getPlugin("ES2UniPlugin");
            if (uni != null) sink = uni.getConfig().getString("tax.sink-account", "");
        }
        if (sink == null || sink.isBlank()) return;
        vault.deposit(Bukkit.getOfflinePlayer(sink), tax);
    }

    public boolean canManage(Player p, java.util.UUID owner) {
        if (p == null) return false;
        if (p.hasPermission("eslink.admin") || isSuper(p)) return true;
        return owner != null && owner.equals(p.getUniqueId());
    }

    public boolean wantListingAlert(Player p) {
        Byte v = p.getPersistentDataContainer().get(
                new org.bukkit.NamespacedKey(this, "list_alert"), org.bukkit.persistence.PersistentDataType.BYTE);
        return v != null && v == (byte) 1;
    }

    public void toggleListingAlert(Player p) {
        boolean on = !wantListingAlert(p);
        p.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey(this, "list_alert"), org.bukkit.persistence.PersistentDataType.BYTE,
                on ? (byte) 1 : (byte) 0);
        msg(p, on ? "已开启上架通知。" : "已关闭上架通知。");
    }

    public boolean isSuper(Player p) {
        if (p.hasPermission("eslink.super")) return true;
        for (String s : getConfig().getStringList("super-admins")) {
            if (s == null || s.isBlank()) continue;
            if (s.equalsIgnoreCase(p.getUniqueId().toString()) || s.equalsIgnoreCase(p.getName())) return true;
        }
        return false;
    }

    public void notifyAdmins(String colored) {
        if (!alertChestAdmin()) return;
        String line = ColorUtil.colorize("&bESLink &7» &f" + colored);
        for (Player a : Bukkit.getOnlinePlayers()) {
            if (a.hasPermission("eslink.admin")) a.sendMessage(line);
        }
        getLogger().info(ColorUtil.colorize(colored).replaceAll("§.", ""));
    }
}
