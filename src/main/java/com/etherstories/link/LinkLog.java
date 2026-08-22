package com.etherstories.link;

import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/** 游戏内可复制的近期日志。服务端控制台不用翻。 */
public final class LinkLog {

    private static final int MAX = 120;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final ConcurrentLinkedDeque<String> LINES = new ConcurrentLinkedDeque<>();
    private static volatile boolean debug;
    private static volatile UUID echoTo;
    private static Handler handler;
    private static ESLinkPlugin plugin;

    private LinkLog() {}

    static void attach(ESLinkPlugin p) {
        plugin = p;
        if (handler != null) return;
        handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r == null) return;
                String msg = r.getMessage() == null ? "" : r.getMessage();
                if (r.getThrown() != null) {
                    msg += " | " + r.getThrown().getClass().getSimpleName();
                    if (r.getThrown().getMessage() != null) msg += ": " + r.getThrown().getMessage();
                }
                char lv = r.getLevel().intValue() >= Level.SEVERE.intValue() ? 'E'
                        : r.getLevel().intValue() >= Level.WARNING.intValue() ? 'W' : 'I';
                add(lv + " " + msg);
            }

            @Override public void flush() {}
            @Override public void close() {}
        };
        p.getLogger().addHandler(handler);
        add("I 日志已开  v" + p.getDescription().getVersion() + "  " + RuntimeEnv.label());
    }

    static void detach() {
        if (plugin != null && handler != null) {
            try { plugin.getLogger().removeHandler(handler); } catch (Throwable ignored) {}
        }
        handler = null;
        plugin = null;
        echoTo = null;
    }

    static boolean debug() {
        return debug;
    }

    static boolean toggleDebug(Player p) {
        debug = !debug;
        echoTo = (debug && p != null) ? p.getUniqueId() : null;
        add("I debug " + (debug ? "开" : "关"));
        return debug;
    }

    static void debug(String msg) {
        if (!debug) return;
        add("D " + msg);
    }

    static void clear() {
        LINES.clear();
        add("I 已清空");
    }

    static void add(String raw) {
        String body = raw == null ? "" : raw.replace('\n', ' ').replace('\r', ' ');
        String line = LocalTime.now().format(FMT) + " " + body;
        if (line.length() > 220) line = line.substring(0, 217) + "...";
        LINES.addLast(line);
        while (LINES.size() > MAX) LINES.pollFirst();
        echo(body, line);
    }

    private static void echo(String raw, String line) {
        if (!debug || plugin == null || !plugin.isEnabled()) return;
        if (raw == null || raw.isEmpty()) return;
        char c = raw.charAt(0);
        if (c != 'D' && c != 'W' && c != 'E') return;
        UUID u = echoTo;
        ESLinkPlugin pl = plugin;
        if (u == null || pl == null || !pl.isEnabled()) return;
        String show = line;
        Bukkit.getScheduler().runTask(pl, () -> {
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline()) pl.msg(p, "&8" + show);
        });
    }

    static void show(Player p) {
        List<String> all = new ArrayList<>(LINES);
        if (all.isEmpty()) {
            plugin.msg(p, "&7还没有日志");
            return;
        }
        plugin.msg(p, "&8—— ESLink 日志 " + all.size() + " 条 · debug=" + (debug ? "开" : "关") + " ——");
        int from = Math.max(0, all.size() - 12);
        for (int i = from; i < all.size(); i++) plugin.msg(p, "&7" + all.get(i));
        String dump = String.join("\n", all);
        if (dump.length() > 12000) dump = dump.substring(dump.length() - 12000);
        try {
            TextComponent row = ChatMsg.text("");
            row.addExtra(ChatMsg.copy("&a[点击复制全部]", dump, "复制到剪贴板，发给我"));
            row.addExtra(ChatMsg.legacy("  "));
            row.addExtra(ChatMsg.click("&e[打开日志书]", "/link log book", "写成书方便翻"));
            ChatMsg.send(p, row);
        } catch (Throwable t) {
            plugin.msg(p, "&7复制按钮不可用，改开日志书");
            giveBook(p);
        }
        plugin.msg(p, "&8/link log debug  详细收发  ·  /link log clear  清空");
    }

    static void giveBook(Player p) {
        List<String> all = new ArrayList<>(LINES);
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        if (!(book.getItemMeta() instanceof BookMeta meta)) {
            plugin.msg(p, "&c这本书打不开");
            return;
        }
        meta.setTitle("ESLink日志");
        meta.setAuthor("ESLink");
        List<String> pages = new ArrayList<>();
        StringBuilder page = new StringBuilder();
        int lines = 0;
        if (all.isEmpty()) page.append("还没有日志");
        for (String line : all) {
            String bit = line + "\n";
            if (lines >= 11 || page.length() + bit.length() > 240) {
                pages.add(page.toString().stripTrailing());
                page.setLength(0);
                lines = 0;
            }
            page.append(bit);
            lines++;
        }
        if (!page.isEmpty()) pages.add(page.toString().stripTrailing());
        meta.setPages(pages);
        book.setItemMeta(meta);
        try {
            p.openBook(book);
        } catch (Throwable t) {
            plugin.msg(p, "&c日志书打不开，用聊天里的复制按钮");
        }
    }
}
