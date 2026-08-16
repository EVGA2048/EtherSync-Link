package com.etherstories.link;

import org.bukkit.ChatColor;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;

public final class NodeSigns {

    private NodeSigns() {}

    public static boolean peerPhase() {
        return (System.currentTimeMillis() / 1600L) % 2L == 1L;
    }

    public static void write(Sign sign, String kind, String role, String unit, String peer, String via, String status) {
        write(sign, kind, role, unit, peer, via, status, 0);
    }

    public static void write(Sign sign, String kind, String role, String unit, String peer, String via, String status, int waitSec) {
        if (sign == null) return;
        var side = sign.getSide(Side.FRONT);
        side.setLine(0, ChatColor.DARK_AQUA + clip("io".equals(kind) ? "互通红石控制器" : "互通箱"));
        boolean paired = peer != null && !peer.isBlank();
        boolean showPeer = paired && peerPhase();
        ChatColor idColor = showPeer ? ChatColor.GOLD : ChatColor.AQUA;
        String id = showPeer ? peer : (unit == null ? "" : unit);
        side.setLine(1, idColor + clip(ESLinkPlugin.roleCn(role) + " " + id));
        side.setLine(2, ChatColor.GRAY + clip(via == null || via.isBlank() ? "未配对" : via));
        if (waitSec > 0) {
            side.setLine(3, ChatColor.GOLD + clip("准备 " + waitSec + "s"));
        } else {
            String err = errorText(status);
            side.setLine(3, colorOf(status) + (err != null ? err : label(status)));
        }
        try {
            sign.setWaxed(true);
        } catch (Throwable ignored) {
        }
        sign.update();
    }

    public static String via(ESLinkPlugin plugin, String role, String pair, String otherCode) {
        if (pair == null || pair.isBlank() || otherCode == null || otherCode.isBlank()) return "未配对";
        String name = plugin.prettyName(otherCode);
        return "TX".equals(role) ? "去向 " + name : "来源 " + name;
    }

    public static String title(String role, String unit, String peer) {
        String r = ESLinkPlugin.roleCn(role);
        if (peer == null || peer.isBlank()) return r + " " + unit;
        return r + " " + unit + " > " + peer;
    }

    public static String chestTitle(String role, String unit, String peer, String status) {
        return chestTitle(role, unit, peer, status, 0);
    }

    public static String chestTitle(String role, String unit, String peer, String status, int waitSec) {
        if (waitSec > 0) return ChatColor.GOLD + "准备 " + waitSec + "s";
        String err = errorText(status);
        if (err != null && peerPhase()) return ChatColor.RED + err;
        return ChatColor.AQUA + title(role, unit, peer);
    }

    public static String route(String from, String to) {
        return from + " > " + to;
    }

    public static String errorText(String st) {
        if (st == null) return null;
        if (st.startsWith("HOLD")) return "离线 0";
        return switch (st) {
            case "jammed" -> "满载";
            case "missing" -> "无此物品";
            case "noback" -> "无回退箱";
            case "backfull" -> "回退箱已满";
            case "paused" -> "暂停";
            case "error" -> "故障";
            default -> null;
        };
    }

    public static String label(String st) {
        if (st == null) st = "idle";
        if (st.startsWith("PWR")) {
            String n = st.length() > 3 ? st.substring(3).trim() : "";
            return n.isEmpty() ? "在线" : "在线 " + n;
        }
        if (st.equals("high") || st.equals("low") || st.equals("linked")) return "在线";
        if (st.startsWith("HOLD")) return "离线 0";
        return switch (st) {
            case "idle" -> "待机";
            case "jammed" -> "满载";
            case "missing" -> "无此物品";
            case "noback" -> "无回退箱";
            case "backfull" -> "回退箱已满";
            case "paused" -> "暂停";
            case "busy" -> "传输中";
            case "error" -> "故障";
            default -> "在线";
        };
    }

    public static ChatColor colorOf(String st) {
        if (st == null) st = "idle";
        if ("paused".equals(st)) return ChatColor.GOLD;
        if (errorText(st) != null) return ChatColor.RED;
        return switch (st) {
            case "linked", "high", "busy" -> ChatColor.GREEN;
            case "idle" -> ChatColor.GRAY;
            case "low" -> ChatColor.DARK_GRAY;
            default -> {
                if (st.startsWith("PWR")) yield st.endsWith(" 0") ? ChatColor.DARK_GRAY : ChatColor.GREEN;
                yield ChatColor.GREEN;
            }
        };
    }

    public static boolean trouble(String st) {
        if (st == null) return false;
        if (st.startsWith("HOLD")) return true;
        return switch (st) {
            case "jammed", "missing", "paused", "error", "noback", "backfull" -> true;
            default -> false;
        };
    }

    public static String logicCn(String logic) {
        return switch (logic == null ? "normal" : logic) {
            case "invert" -> "反向";
            case "full" -> "满信号";
            default -> "正常";
        };
    }

    public static String logicNext(String logic) {
        return switch (logic == null ? "normal" : logic) {
            case "normal" -> "invert";
            case "invert" -> "full";
            default -> "normal";
        };
    }

    public static int mapLogic(String logic, boolean live, int peer) {
        if (!live) return 0;
        int lv = Math.max(0, Math.min(15, peer));
        return switch (logic == null ? "normal" : logic) {
            case "invert" -> 15 - lv;
            case "full" -> lv > 0 ? 15 : 0;
            default -> lv;
        };
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() <= 16 ? s : s.substring(0, 16);
    }
}
