package com.etherstories.link;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Arclight 的 AsyncChatEvent / getMessage() 会卡住第一句。
 * 进站包里的字符串才是玩家刚打的字。
 */
public final class ChatTap implements Listener {
    private static final String HANDLER = "eslink_chat";
    private static final Map<UUID, Hit> inbound = new ConcurrentHashMap<>();
    private static volatile boolean warned;
    private static volatile ChatTap instance;

    private record Hit(String msg, long at) {}

    private final Plugin plugin;

    public ChatTap(Plugin plugin) {
        this.plugin = plugin;
        instance = this;
    }

    static String inbound(Player p) {
        if (p == null) return null;
        Hit h = inbound.get(p.getUniqueId());
        return h == null ? null : h.msg;
    }

    static String text(Player p, String fallback) {
        if (p != null) {
            Hit h = inbound.get(p.getUniqueId());
            if (h != null && !h.msg.isBlank() && System.nanoTime() - h.at < 500_000_000L)
                return h.msg;
            ChatTap tap = instance;
            if (tap != null) tap.inject(p);
        }
        return fallback == null ? "" : fallback;
    }

    void hookOnline() {
        for (Player p : Bukkit.getOnlinePlayers()) inject(p);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        inject(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        inbound.remove(e.getPlayer().getUniqueId());
        uninject(e.getPlayer());
    }

    private void inject(Player p) {
        try {
            Channel ch = channelOf(p);
            if (ch == null) return;
            UUID id = p.getUniqueId();
            ch.eventLoop().execute(() -> {
                try {
                    if (!ch.isOpen() || ch.pipeline().get(HANDLER) != null) return;
                    ChannelDuplexHandler h = new ChannelDuplexHandler() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                            String text = packetText(msg);
                            if (text != null) inbound.put(id, new Hit(text, System.nanoTime()));
                            super.channelRead(ctx, msg);
                        }
                    };
                    List<String> names = ch.pipeline().names();
                    if (names.contains("packet_handler")) ch.pipeline().addBefore("packet_handler", HANDLER, h);
                    else ch.pipeline().addFirst(HANDLER, h);
                } catch (Throwable t) {
                    warn(t);
                }
            });
        } catch (Throwable t) {
            warn(t);
        }
    }

    private void uninject(Player p) {
        try {
            Channel ch = channelOf(p);
            if (ch == null) return;
            ch.eventLoop().execute(() -> {
                try {
                    if (ch.pipeline().get(HANDLER) != null) ch.pipeline().remove(HANDLER);
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    private static String packetText(Object packet) {
        if (packet == null) return null;
        String cn = packet.getClass().getName();
        if (cn.contains("Command") || cn.contains("Session")) return null;
        boolean chat = cn.contains("ServerboundChatPacket") || cn.contains("PacketPlayInChat")
                || (cn.contains("Chat") && !cn.contains("Ack") && !cn.contains("Preview")
                && !cn.contains("Command") && !cn.contains("Session") && !cn.contains("Clientbound"));
        if (!chat) return null;
        try {
            Method m = packet.getClass().getMethod("message");
            Object v = m.invoke(packet);
            if (v instanceof String s && usable(s)) return new String(s);
        } catch (Throwable ignored) {}
        try {
            Method m = packet.getClass().getMethod("getMessage");
            Object v = m.invoke(packet);
            if (v instanceof String s && usable(s)) return new String(s);
        } catch (Throwable ignored) {}
        for (Field f : packet.getClass().getDeclaredFields()) {
            if (f.getType() != String.class) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(packet);
                if (v instanceof String s && usable(s)) return new String(s);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static boolean usable(String s) {
        if (s == null) return false;
        String t = s.trim();
        return !t.isEmpty() && t.length() <= 256 && t.indexOf('\n') < 0;
    }

    private static Channel channelOf(Player p) throws Exception {
        Object nms = p.getClass().getMethod("getHandle").invoke(p);
        Object listener = named(nms, "connection");
        if (listener == null) listener = typed(nms, "ServerGamePacketListener", "PlayerConnection");
        Object conn = listener == null ? null : named(listener, "connection");
        if (conn == null && listener != null) conn = named(listener, "networkManager");
        if (conn == null && listener != null) conn = typed(listener, "Connection", "NetworkManager");
        Object ch = conn == null ? null : named(conn, "channel");
        if (ch instanceof Channel c) return c;
        return walk(nms, 0, new IdentityHashMap<>());
    }

    private static Object named(Object o, String name) {
        if (o == null) return null;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object typed(Object o, String... bits) {
        if (o == null) return null;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                String n = f.getType().getName();
                for (String bit : bits) {
                    if (!n.contains(bit)) continue;
                    try {
                        f.setAccessible(true);
                        return f.get(o);
                    } catch (Throwable ignored) {}
                }
            }
        }
        return null;
    }

    private static Channel walk(Object o, int depth, IdentityHashMap<Object, Boolean> seen) {
        if (o == null || depth > 5 || seen.put(o, Boolean.TRUE) != null) return null;
        if (o instanceof Channel c) return c;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            for (Field f : fields) {
                Class<?> ft = f.getType();
                if (Channel.class.isAssignableFrom(ft)) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(o);
                        if (v instanceof Channel ch) return ch;
                    } catch (Throwable ignored) {}
                }
                String n = ft.getName();
                if (!(n.contains("Connection") || n.contains("NetworkManager")
                        || n.contains("ServerGamePacketListener") || n.contains("PlayerConnection")
                        || f.getName().equals("connection"))) continue;
                try {
                    f.setAccessible(true);
                    Channel ch = walk(f.get(o), depth + 1, seen);
                    if (ch != null) return ch;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private void warn(Throwable t) {
        if (warned) return;
        warned = true;
        plugin.getLogger().warning("聊天包挂钩失败（Arclight 可能仍会卡住上一句）: " + t.getMessage());
    }
}
