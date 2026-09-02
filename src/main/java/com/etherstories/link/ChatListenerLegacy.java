package com.etherstories.link;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.ItemStack;

/** Arclight / Youer / 无 Paper AsyncChatEvent 时用 */
public final class ChatListenerLegacy implements Listener {
    private final ESLinkPlugin plugin;

    public ChatListenerLegacy(ESLinkPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        Sessions.State st = plugin.sessions().of(p);
        if (!st.awaitingSearch && !st.awaitingPrice && !st.awaitingPair
                && !st.awaitingWallet && !st.awaitingClaim && !st.awaitingWalletPin
                && !st.awaitingWalletNewPin) return;
        String msg = ChatTap.text(p, e.getMessage() == null ? "" : e.getMessage()).trim();
        e.setCancelled(true);
        e.setMessage("");
        e.getRecipients().clear();
        Bukkit.getScheduler().runTask(plugin, () -> handle(p, st, msg));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void decorate(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        String raw = ChatTap.text(p, e.getMessage() == null ? "" : e.getMessage());
        if (plugin.chat() != null && plugin.getConfig().getBoolean("chat.enabled", true)
                && plugin.chat().isAll(p)) {
            e.getRecipients().clear();
            if (!RuntimeEnv.keepChatSession()) {
                e.setCancelled(true);
                e.setMessage("");
            }
            ItemStack hand = p.getInventory().getItemInMainHand();
            ItemStack item = (hand == null || hand.getType().isAir()) ? null : hand.clone();
            plugin.chat().emitFromChat(p, raw, item, ChatBridge.PRI_LEGACY);
            return;
        }
        if (plugin.getConfig().getBoolean("chat.item", true) && ItemChat.hasToken(raw)) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            String name = (hand == null || hand.getType().isAir()) ? "空手" : ItemCodec.display(hand);
            int amt = (hand == null || hand.getType().isAir()) ? 1 : hand.getAmount();
            String label = amt > 1 ? "[" + name + " x" + amt + "]" : "[" + name + "]";
            e.setMessage(ColorUtil.colorize(raw.replace("[i]", label).replace("[I]", label)));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void hide(AsyncPlayerChatEvent e) {
        if (!RuntimeEnv.keepChatSession()) return;
        if (plugin.chat() == null || !plugin.getConfig().getBoolean("chat.enabled", true)
                || !plugin.chat().isAll(e.getPlayer())) return;
        e.getRecipients().clear();
    }

    private void handle(Player p, Sessions.State st, String msg) {
        if (msg.equalsIgnoreCase("cancel") || msg.equals("取消") || msg.equalsIgnoreCase("c")) {
            st.awaitingSearch = false;
            st.awaitingPrice = false;
            st.awaitingPair = false;
            boolean wallet = st.awaitingWallet || st.awaitingWalletPin || st.awaitingWalletNewPin;
            boolean claim = st.awaitingClaim;
            st.awaitingWallet = false;
            st.awaitingWalletPin = false;
            st.awaitingWalletNewPin = false;
            st.walletPinForChange = false;
            st.awaitingClaim = false;
            st.claimListingId = 0;
            st.repriceId = 0;
            st.listItem = null;
            plugin.msg(p, "已取消。");
            if (plugin.gui() != null) {
                if (wallet) plugin.gui().openWallet(p);
                else if (claim) plugin.gui().openMarket(p);
                else plugin.gui().openHome(p);
            }
            return;
        }
        if (st.awaitingPair) {
            st.awaitingPair = false;
            if (plugin.gui() != null) plugin.gui().tryPairUnit(p, msg);
            return;
        }
        if (st.awaitingSearch) {
            st.awaitingSearch = false;
            st.query = msg;
            st.marketPage = 0;
            if (plugin.gui() != null) plugin.gui().openMarket(p);
            return;
        }
        if (st.awaitingWalletPin) {
            if (plugin.gui() != null) plugin.gui().finishWalletPin(p, msg);
            return;
        }
        if (st.awaitingWalletNewPin) {
            if (plugin.gui() != null) plugin.gui().finishWalletNewPin(p, msg);
            return;
        }
        if (st.awaitingClaim) {
            if (plugin.gui() != null) plugin.gui().finishClaim(p, msg);
            return;
        }
        if (st.awaitingWallet) {
            if (ESLinkPlugin.isAllAmount(msg)) {
                if (plugin.gui() != null) plugin.gui().finishWalletAll(p);
                return;
            }
            try {
                if (plugin.gui() != null) plugin.gui().finishWallet(p, Double.parseDouble(msg.replace(',', '.')));
            } catch (NumberFormatException ex) {
                plugin.msg(p, "请输入数字金额，或输入 cancel 取消。");
            }
            return;
        }
        if (st.awaitingPrice) {
            try {
                double price = Double.parseDouble(msg.replace(',', '.'));
                if (plugin.gui() != null) {
                    if (st.repriceId > 0) plugin.gui().finishReprice(p, price);
                    else plugin.gui().finishSell(p, price);
                }
            } catch (NumberFormatException ex) {
                plugin.msg(p, "请输入数字单价，或输入 cancel 取消。");
            }
        }
    }
}
