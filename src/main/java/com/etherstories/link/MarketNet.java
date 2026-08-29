package com.etherstories.link;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多市场登记与货单入口。已登记 HTTP 市场时走独立服务；未登记时仍用本服 MySQL 货单。
 * 聊天与运输不经过这里。
 */
public final class MarketNet {
    private final ESLinkPlugin plugin;
    private final org.bukkit.NamespacedKey pickKey;
    private final Map<String, MarketClient> clients = new ConcurrentHashMap<>();
    private volatile String defaultId = "";

    public MarketNet(ESLinkPlugin plugin) {
        this.plugin = plugin;
        this.pickKey = new org.bukkit.NamespacedKey(plugin, "market");
        reload();
    }

    public synchronized void reload() {
        clients.clear();
        defaultId = plugin.getConfig().getString("markets.default", "");
        if (defaultId == null) defaultId = "";
        defaultId = defaultId.trim().toLowerCase(Locale.ROOT);
        ConfigurationSection list = plugin.getConfig().getConfigurationSection("markets.list");
        long offlineMs = plugin.offlineMs();
        if (list != null) {
            for (String id : list.getKeys(false)) {
                String key = sanitizeId(id);
                if (key.isEmpty()) continue;
                ConfigurationSection s = list.getConfigurationSection(id);
                if (s == null) continue;
                String url = s.getString("url", "");
                String token = s.getString("token", "");
                String name = s.getString("name", key);
                if (url == null || url.isBlank()) continue;
                MarketHub hub = new MarketHub(key, name, url, token == null ? "" : token);
                clients.put(key, new MarketClient(hub, offlineMs));
            }
        }
        if (!defaultId.isEmpty() && !clients.containsKey(defaultId) && !clients.isEmpty()) {
            defaultId = clients.keySet().iterator().next();
        }
        if (defaultId.isEmpty() && !clients.isEmpty()) {
            defaultId = clients.keySet().iterator().next();
        }
    }

    public boolean httpEnabled() {
        return !clients.isEmpty();
    }

    public List<MarketHub> hubs() {
        List<MarketHub> out = new ArrayList<>();
        for (MarketClient c : clients.values()) out.add(c.hub());
        return out;
    }

    public String defaultId() { return defaultId; }

    public MarketHub hub(String id) {
        MarketClient c = clients.get(sanitizeId(id));
        return c == null ? null : c.hub();
    }

    public String selectedId(Player p) {
        if (!httpEnabled()) return "";
        String v = p.getPersistentDataContainer().get(pickKey, PersistentDataType.STRING);
        if (v != null && clients.containsKey(sanitizeId(v))) return sanitizeId(v);
        return defaultId;
    }

    public MarketHub selected(Player p) {
        MarketClient c = clientOf(p);
        return c == null ? null : c.hub();
    }

    public String selectedName(Player p) {
        MarketHub h = selected(p);
        return h == null ? "本服货单" : h.displayName();
    }

    public boolean select(Player p, String id) {
        String key = sanitizeId(id);
        if (!clients.containsKey(key)) return false;
        p.getPersistentDataContainer().set(pickKey, PersistentDataType.STRING, key);
        return true;
    }

    public synchronized String add(String id, String url, String token, String name) {
        String key = sanitizeId(id);
        if (key.isEmpty()) return "代号只能用字母、数字、下划线。";
        if (url == null || url.isBlank()) return "请填写市场地址，例如 http://127.0.0.1:8765";
        String n = (name == null || name.isBlank()) ? key : name.trim();
        plugin.getConfig().set("markets.list." + key + ".name", n);
        plugin.getConfig().set("markets.list." + key + ".url", url.trim());
        plugin.getConfig().set("markets.list." + key + ".token", token == null ? "" : token.trim());
        if (defaultId.isEmpty()) {
            defaultId = key;
            plugin.getConfig().set("markets.default", key);
        }
        plugin.saveConfig();
        reload();
        return null;
    }

    public synchronized String remove(String id) {
        String key = sanitizeId(id);
        if (!clients.containsKey(key) && plugin.getConfig().getConfigurationSection("markets.list." + key) == null) {
            return "没有代号为 " + key + " 的市场。";
        }
        plugin.getConfig().set("markets.list." + key, null);
        if (key.equals(defaultId)) {
            defaultId = "";
            plugin.getConfig().set("markets.default", "");
        }
        plugin.saveConfig();
        reload();
        return null;
    }

    public synchronized String setDefault(String id) {
        String key = sanitizeId(id);
        if (!clients.containsKey(key)) return "没有代号为 " + key + " 的市场。";
        defaultId = key;
        plugin.getConfig().set("markets.default", key);
        plugin.saveConfig();
        return null;
    }

    public void heartbeatAll() {
        heartbeatAll(false, null);
    }

    public void heartbeatAll(boolean pinRate, String rateMode) {
        String code = plugin.serverCode();
        String name = plugin.serverName();
        String blurb = plugin.serverBlurb();
        String color = plugin.serverColor();
        String icon = plugin.serverIcon();
        double rate = plugin.linkRate();
        for (MarketClient c : clients.values()) {
            try {
                com.google.gson.JsonObject o = c.heartbeat(code, name, blurb, color, icon, rate, pinRate, rateMode);
                double link = o != null && o.has("link_rate") && !o.get("link_rate").isJsonNull()
                        ? o.get("link_rate").getAsDouble() : -1;
                String mode = o != null && o.has("rate_mode") ? o.get("rate_mode").getAsString() : "";
                plugin.onMarketRate(link, mode);
            } catch (Exception e) {
                c.markFail(e.getMessage());
            }
        }
    }

    public List<Models.ServerRow> servers(Player p) throws Exception {
        MarketClient c = clientOf(p);
        if (c != null) return c.servers();
        return plugin.store().ready() ? plugin.store().servers() : List.of();
    }

    public List<Models.Listing> listings(Player p, String serverFilter, String query, UUID seller,
                                         int offset, int limit) throws Exception {
        MarketClient c = clientOf(p);
        if (c != null) return c.listings(serverFilter, query, seller, offset, limit);
        return plugin.store().listings(serverFilter, query, seller, offset, limit);
    }

    public Models.Listing listing(Player p, long id) throws Exception {
        MarketClient c = clientOf(p);
        if (c != null) return c.listing(id);
        return plugin.store().listing(id);
    }

    public void insert(Player p, UUID seller, String sellerName, String server, String itemKey, String itemName,
                       int amount, double price, String b64, String nestedKeys, String claimCode) throws Exception {
        MarketClient c = clientOf(p);
        if (c != null) {
            c.insert(seller, sellerName, server, itemKey, itemName, amount, price, b64, nestedKeys, claimCode);
            return;
        }
        plugin.store().insertListing(seller, sellerName, server, itemKey, itemName, amount, price, b64, nestedKeys, claimCode);
    }

    public String allocClaimCode(Player p) throws Exception {
        for (int i = 0; i < 40; i++) {
            String code = ClaimCodes.generate();
            if (!claimTaken(p, code)) return code;
        }
        throw new Exception("取件码已用尽，请稍后再上架");
    }

    public boolean claimTaken(Player p, String code) throws Exception {
        MarketClient c = clientOf(p);
        if (c != null) return c.claimTaken(code);
        return plugin.store().claimCodeTaken(code);
    }

    public Models.Listing listingByClaim(Player p, String code) throws Exception {
        MarketClient c = clientOf(p);
        if (c != null) return c.listingByClaim(code);
        return plugin.store().listingByClaim(code);
    }

    public boolean delete(Player p, long id) throws Exception {
        MarketClient c = clientOf(p);
        if (c != null) return c.delete(id);
        return plugin.store().deleteListing(id);
    }

    public Models.Listing claim(Player p, long id) throws Exception {
        return claim(p, id, false);
    }

    public Models.Listing claim(Player p, long id, boolean pickup) throws Exception {
        MarketClient c = clientOf(p);
        if (c != null) {
            return c.claim(id, p.getUniqueId(), p.getName(), plugin.serverCode(), pickup);
        }
        Models.Listing row = plugin.store().listing(id);
        if (row == null) return null;
        return plugin.store().deleteListing(id) ? row : null;
    }

    public void setPrice(Player p, long id, double price) throws Exception {
        MarketClient c = clientOf(p);
        if (c != null) {
            c.setPrice(id, price);
            return;
        }
        plugin.store().setListingPrice(id, price);
    }

    public int deleteOf(Player p, String server, UUID seller) throws Exception {
        MarketClient c = clientOf(p);
        if (c != null) return c.deleteOf(server, seller);
        return plugin.store().deleteListingsOf(server, seller);
    }

    public String requireReady(Player p) {
        if (!httpEnabled()) {
            if (plugin.store() == null || !plugin.store().ready()) return "数据库未连接。";
            return null;
        }
        if (clientOf(p) == null) return "尚未选择市场。请打开大厅，点击要进入的市场。";
        return null;
    }

    private MarketClient clientOf(Player p) {
        if (!httpEnabled()) return null;
        String id = selectedId(p);
        return id.isEmpty() ? null : clients.get(id);
    }

    static String sanitizeId(String id) {
        if (id == null) return "";
        return id.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
    }

    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        if (clients.isEmpty()) {
            lines.add("未登记独立市场，货单仍使用本服 MySQL。");
            return lines;
        }
        lines.add("默认市场: " + (defaultId.isEmpty() ? "（未设）" : defaultId));
        for (MarketClient c : clients.values()) {
            MarketHub h = c.hub();
            String st = h.online ? "在线" : "离线";
            lines.add(h.id + " · " + h.displayName() + " · " + st + " · " + h.url);
        }
        return lines;
    }
}
