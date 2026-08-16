package com.etherstories.link;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** GUI / 聊天检索会话 */
public final class Sessions {

    public enum Page { HOME, MARKET, SELLER, LIST, MINE, PAIR, CONFIRM, ADMIN, SETTINGS, COLORS, SERVERS, CHEST, WATCH, NODES, ADMIN_NODES }

    public static final class State {
        public Page page = Page.HOME;
        public int marketPage;
        public String serverFilter; // null = 全部
        public String query = "";
        public UUID sellerView;
        public long confirmListingId;
        public String pairKind = "chest"; // chest | io
        public String pairRole; // TX / RX
        public String pairTargetServer;
        public Integer pendingChestId;
        public boolean awaitingSearch;
        public boolean awaitingPrice;
        public boolean awaitingPair;
        public long repriceId;
        public int listAmount;
        public org.bukkit.inventory.ItemStack listItem;
        public String pendingDelete;
        public String pendingUnlink;
        public Integer bindBounceFor;
        public boolean hasLook;
        public String lookWorld;
        public int lookX, lookY, lookZ;
    }

    private final Map<UUID, State> map = new ConcurrentHashMap<>();

    public State of(Player p) {
        return map.computeIfAbsent(p.getUniqueId(), u -> new State());
    }

    public void clear(Player p) { map.remove(p.getUniqueId()); }
}
