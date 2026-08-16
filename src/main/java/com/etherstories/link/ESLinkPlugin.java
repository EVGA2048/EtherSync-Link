package com.etherstories.link;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.Set;
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
    private final Set<String> rxKeys = ConcurrentHashMap.newKeySet();
    private final Set<java.util.UUID> guideWelcomed = ConcurrentHashMap.newKeySet();
    private final java.util.Map<String, Models.ServerRow> serverCache = new ConcurrentHashMap<>();
    private volatile boolean ioEnabled = true;

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
            vault.hook();
            ContainerSupport.configure(getConfig().getString("chest.containers", "auto"));
            ContainerSupport.scheduleProbe(this);

            Bukkit.getPluginManager().registerEvents(new GuiListener(this), this);
            registerChatListener();
            Bukkit.getPluginManager().registerEvents(new ChestListener(this), this);
            Bukkit.getPluginManager().registerEvents(new ProxyLockListener(), this);
            Bukkit.getPluginManager().registerEvents(new IoListener(this), this);

            if (!store.connect()) {
                getLogger().warning("MySQL 未连上。填 plugins/ESLink/config.yml 后 /link reload");
            } else {
                startTasks();
                getLogger().info("ESLink 已连接  " + serverCode() + " / " + serverName());
            }
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
    }

    private void registerChatListener() {
        String core = Bukkit.getName() + " " + Bukkit.getVersion();
        boolean hybrid = core.toLowerCase(Locale.ROOT).contains("arclight")
                || core.toLowerCase(Locale.ROOT).contains("mohist")
                || core.toLowerCase(Locale.ROOT).contains("catserver");
        if (!hybrid) {
            try {
                Class.forName("io.papermc.paper.event.player.AsyncChatEvent");
                Bukkit.getPluginManager().registerEvents(new ChatListener(this), this);
                return;
            } catch (Throwable ignored) {
            }
        }
        Bukkit.getPluginManager().registerEvents(new ChatListenerLegacy(this), this);
        getLogger().info("聊天改用兼容模式（" + Bukkit.getName() + "）");
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
            vault.hook();
            ContainerSupport.configure(getConfig().getString("chest.containers", "auto"));
            ContainerSupport.clearTrip();
            ContainerSupport.probe(this);
            if (store != null) store.close();
            store = new Store(this);
            chat.resetCursor();
            if (alerts != null) alerts.resetCursor();
            boolean ok = store.connect();
            if (ok) startTasks();
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
                    store.heartbeat(serverCode(), serverName(), serverBlurb(), serverColor(), serverIcon());
                    rememberServers(store.servers());
                }
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
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                if (io != null) io.pulse();
            }, 1L, 1L);
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                if (io != null) io.poll();
            }, 20L, 5L);
        }
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (chests != null) chests.paint();
            if (ioEnabled && io != null) io.paint();
        }, 20L, 20L);
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

    public double taxRate() {
        double r = getConfig().getDouble("trade.tax-rate", 0);
        if (r < 0) return 0;
        if (r > 1) return 1;
        return r;
    }

    public double taxOf(double price) {
        if (!tradeEnabled() || price <= 0) return 0;
        return Math.round(price * taxRate() * 100.0) / 100.0;
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
        msg(p, on ? "&a将接收上架通知" : "&7已关闭上架通知");
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
