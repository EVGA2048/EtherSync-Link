package com.etherstories.link;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ChatListener implements Listener {
    private final ESLinkPlugin plugin;
    private final Map<UUID, Snap> snaps = new ConcurrentHashMap<>();

    private record Snap(String plain, ItemStack item) {}

    public ChatListener(ESLinkPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Sessions.State st = plugin.sessions().of(p);
        if (!st.awaitingSearch && !st.awaitingPrice && !st.awaitingPair) return;
        e.setCancelled(true);
        snaps.remove(p.getUniqueId());
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> handle(p, st, msg));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void snapshot(AsyncChatEvent e) {
        Player p = e.getPlayer();
        String plain = PlainTextComponentSerializer.plainText().serialize(e.message());
        ItemStack hand = p.getInventory().getItemInMainHand();
        ItemStack clone = (hand == null || hand.getType().isAir()) ? null : hand.clone();
        snaps.put(p.getUniqueId(), new Snap(plain, clone));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void decorate(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Snap snap = snaps.get(p.getUniqueId());
        String plain = snap != null ? snap.plain()
                : PlainTextComponentSerializer.plainText().serialize(e.message());
        ItemStack item = snap != null ? snap.item() : p.getInventory().getItemInMainHand();
        // 互通聊天必须取消原包：改 renderer / message 会让 1.19+ 签名对不上，
        // Youer 上所有人立刻「网络协议错误」掉线，服务器还活着。
        if (plugin.chat() != null && plugin.getConfig().getBoolean("chat.enabled", true)
                && plugin.chat().isAll(p)) {
            e.setCancelled(true);
            try { e.viewers().clear(); } catch (Throwable ignored) {}
            snaps.remove(p.getUniqueId());
            ItemStack hand = item == null ? null : item.clone();
            String msg = plain;
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.chat().showLocal(p, msg, hand);
                plugin.chat().send(p, msg, hand);
            });
            return;
        }
        if (RuntimeEnv.hybrid()) return;
        if (plugin.getConfig().getBoolean("chat.item", true) && ItemChat.hasToken(plain)) {
            e.message(ItemChatPaper.replace(plain, item));
        }
    }

    private void handle(Player p, Sessions.State st, String msg) {
        if (msg.equalsIgnoreCase("cancel") || msg.equals("取消") || msg.equalsIgnoreCase("c")) {
            st.awaitingSearch = false;
            st.awaitingPrice = false;
            st.awaitingPair = false;
            st.repriceId = 0;
            st.listItem = null;
            plugin.msg(p, "已取消");
            plugin.gui().openHome(p);
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
        if (st.awaitingPrice) {
            try {
                double price = Double.parseDouble(msg.replace(',', '.'));
                if (st.repriceId > 0) plugin.gui().finishReprice(p, price);
                else plugin.gui().finishSell(p, price);
            } catch (NumberFormatException ex) {
                plugin.msg(p, "&c输入数字单价，或 cancel");
            }
        }
    }
}
