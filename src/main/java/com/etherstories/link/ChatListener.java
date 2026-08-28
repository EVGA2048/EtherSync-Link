package com.etherstories.link;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

public final class ChatListener implements Listener {
    private final ESLinkPlugin plugin;

    public ChatListener(ESLinkPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Sessions.State st = plugin.sessions().of(p);
        if (!st.awaitingSearch && !st.awaitingPrice && !st.awaitingPair
                && !st.awaitingWallet && !st.awaitingClaim && !st.awaitingWalletPin) return;
        e.setCancelled(true);
        try { e.viewers().clear(); } catch (Throwable ignored) {}
        String msg = ChatTap.text(p, ItemChatPaper.plain(e)).trim();
        Bukkit.getScheduler().runTask(plugin, () -> handle(p, st, msg));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void decorate(AsyncChatEvent e) {
        Player p = e.getPlayer();
        String plain = ChatTap.text(p, ItemChatPaper.plain(e));
        ItemStack hand = p.getInventory().getItemInMainHand();
        ItemStack item = (hand == null || hand.getType().isAir()) ? null : hand.clone();
        if (plugin.chat() != null && plugin.getConfig().getBoolean("chat.enabled", true)
                && plugin.chat().isAll(p)) {
            try { e.viewers().clear(); } catch (Throwable ignored) {}
            // Arclight 取消原包会卡住签名会话，下一句仍是上一句，隔一阵才换。
            // Youer 必须取消，否则改包会全员掉线。
            if (!RuntimeEnv.keepChatSession()) e.setCancelled(true);
            int pri = RuntimeEnv.kind() == RuntimeEnv.Kind.ARCLIGHT
                    ? ChatBridge.PRI_ARCLIGHT : ChatBridge.PRI_PAPER;
            plugin.chat().emitFromChat(p, plain, item, pri);
            return;
        }
        if (RuntimeEnv.hybrid()) return;
        if (plugin.getConfig().getBoolean("chat.item", true) && ItemChat.hasToken(plain)) {
            e.message(ItemChatPaper.replace(plain, item));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void hide(AsyncChatEvent e) {
        if (!RuntimeEnv.keepChatSession()) return;
        if (plugin.chat() == null || !plugin.getConfig().getBoolean("chat.enabled", true)
                || !plugin.chat().isAll(e.getPlayer())) return;
        try { e.viewers().clear(); } catch (Throwable ignored) {}
    }

    private void handle(Player p, Sessions.State st, String msg) {
        if (msg.equalsIgnoreCase("cancel") || msg.equals("取消") || msg.equalsIgnoreCase("c")) {
            st.awaitingSearch = false;
            st.awaitingPrice = false;
            st.awaitingPair = false;
            boolean wallet = st.awaitingWallet || st.awaitingWalletPin;
            boolean claim = st.awaitingClaim;
            st.awaitingWallet = false;
            st.awaitingWalletPin = false;
            st.awaitingClaim = false;
            st.claimListingId = 0;
            st.repriceId = 0;
            st.listItem = null;
            plugin.msg(p, "已取消。");
            if (wallet) plugin.gui().openWallet(p);
            else if (claim) plugin.gui().openMarket(p);
            else plugin.gui().openHome(p);
            return;
        }
        if (st.awaitingPair) {
            st.awaitingPair = false;
            plugin.gui().tryPairUnit(p, msg);
            return;
        }
        if (st.awaitingSearch) {
            st.awaitingSearch = false;
            st.query = msg;
            st.marketPage = 0;
            plugin.gui().openMarket(p);
            return;
        }
        if (st.awaitingWalletPin) {
            plugin.gui().finishWalletPin(p, msg);
            return;
        }
        if (st.awaitingClaim) {
            plugin.gui().finishClaim(p, msg);
            return;
        }
        if (st.awaitingWallet) {
            if (ESLinkPlugin.isAllAmount(msg)) {
                plugin.gui().finishWalletAll(p);
                return;
            }
            try {
                plugin.gui().finishWallet(p, Double.parseDouble(msg.replace(',', '.')));
            } catch (NumberFormatException ex) {
                plugin.msg(p, "请输入数字金额，或输入 cancel 取消。");
            }
            return;
        }
        if (st.awaitingPrice) {
            try {
                double price = Double.parseDouble(msg.replace(',', '.'));
                if (st.repriceId > 0) plugin.gui().finishReprice(p, price);
                else plugin.gui().finishSell(p, price);
            } catch (NumberFormatException ex) {
                plugin.msg(p, "请输入数字单价，或输入 cancel 取消。");
            }
        }
    }
}
