package com.etherstories.link;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Arclight / 无 Paper AsyncChatEvent 时用 */
public final class ChatListenerLegacy implements Listener {
    private final ESLinkPlugin plugin;
    private final Map<UUID, String> raws = new ConcurrentHashMap<>();

    public ChatListenerLegacy(ESLinkPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        Sessions.State st = plugin.sessions().of(p);
        if (!st.awaitingSearch && !st.awaitingPrice && !st.awaitingPair) return;
        e.setCancelled(true);
        String msg = e.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> handle(p, st, msg));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void decorate(AsyncPlayerChatEvent e) {
        String raw = e.getMessage();
        raws.put(e.getPlayer().getUniqueId(), raw);
        String shown = raw;
        if (plugin.getConfig().getBoolean("chat.item", true) && ItemChat.hasToken(raw)) {
            ItemStack hand = e.getPlayer().getInventory().getItemInMainHand();
            String name = (hand == null || hand.getType().isAir()) ? "空手" : ItemCodec.display(hand);
            int amt = (hand == null || hand.getType().isAir()) ? 1 : hand.getAmount();
            String label = amt > 1 ? "[" + name + " x" + amt + "]" : "[" + name + "]";
            shown = raw.replace("[i]", label).replace("[I]", label);
        }
        e.setMessage(ColorUtil.colorize(shown));
        if (plugin.chat() != null && plugin.chat().isAll(e.getPlayer())) {
            String tag = plugin.chat().localTag().replace("%", "%%");
            e.setFormat(tag + e.getFormat());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGlobal(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        Sessions.State st = plugin.sessions().of(p);
        if (st.awaitingSearch || st.awaitingPrice || st.awaitingPair) return;
        if (plugin.chat() == null || !plugin.chat().isAll(p)) {
            raws.remove(p.getUniqueId());
            return;
        }
        String raw = raws.remove(p.getUniqueId());
        if (raw == null) raw = e.getMessage();
        ItemStack hand = p.getInventory().getItemInMainHand();
        ItemStack item = (hand == null || hand.getType().isAir()) ? null : hand.clone();
        plugin.chat().send(p, raw, item);
    }

    private void handle(Player p, Sessions.State st, String msg) {
        if (msg.equalsIgnoreCase("cancel") || msg.equals("取消") || msg.equalsIgnoreCase("c")) {
            st.awaitingSearch = false;
            st.awaitingPrice = false;
            st.awaitingPair = false;
            st.repriceId = 0;
            st.listItem = null;
            plugin.msg(p, "已取消");
            if (plugin.gui() != null) plugin.gui().openHome(p);
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
        if (st.awaitingPrice) {
            try {
                double price = Double.parseDouble(msg.replace(',', '.'));
                if (plugin.gui() != null) {
                    if (st.repriceId > 0) plugin.gui().finishReprice(p, price);
                    else plugin.gui().finishSell(p, price);
                }
            } catch (NumberFormatException ex) {
                plugin.msg(p, "&c输入数字单价，或 cancel");
            }
        }
    }
}
