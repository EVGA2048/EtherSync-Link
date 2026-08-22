package com.etherstories.link;

import net.md_5.bungee.api.ChatColor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ColorUtil {
    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private ColorUtil() {}

    public static String colorize(String s) {
        if (s == null) return "";
        Matcher m = HEX.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String hex = m.group(1);
            m.appendReplacement(out, ChatColor.of("#" + hex).toString());
        }
        m.appendTail(out);
        return ChatColor.translateAlternateColorCodes('&', out.toString());
    }

    /** 只保留原版 &0-&f / &k-&r，去掉 hex。混合端客户端吃不下 §x。 */
    public static String safe(String s) {
        if (s == null) return "";
        s = HEX.matcher(s).replaceAll("");
        s = s.replaceAll("(?i)§x(§[0-9a-f]){6}", "");
        s = s.replaceAll("(?i)&x(&[0-9a-f]){6}", "");
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    /** 大厅陶瓦色 → & 码 */
    public static String dye(String color) {
        if (color == null) return "b";
        return switch (color.toUpperCase()) {
            case "WHITE" -> "f";
            case "LIGHT_GRAY", "SILVER" -> "7";
            case "GRAY" -> "8";
            case "BLACK" -> "0";
            case "BROWN", "ORANGE" -> "6";
            case "RED" -> "c";
            case "YELLOW" -> "e";
            case "LIME" -> "a";
            case "GREEN" -> "2";
            case "CYAN", "LIGHT_BLUE" -> "b";
            case "BLUE" -> "9";
            case "PURPLE" -> "5";
            case "MAGENTA", "PINK" -> "d";
            default -> "b";
        };
    }
}
