package com.etherstories.link;

import org.bukkit.inventory.ItemStack;

public final class ItemChat {
    public static final String TOKEN = "[i]";

    private ItemChat() {}

    public static boolean hasToken(String s) {
        if (s == null) return false;
        return indexOf(s, 0) >= 0;
    }

    public static String label(ItemStack item, String fallbackName) {
        if (item == null || item.getType().isAir()) {
            if (fallbackName != null && !fallbackName.isBlank()) return "[" + fallbackName + "]";
            return "[空手]";
        }
        String name = ItemCodec.display(item);
        return item.getAmount() > 1 ? "[" + name + " x" + item.getAmount() + "]" : "[" + name + "]";
    }

    public static String replacePlain(String plain, ItemStack item, String fallbackName) {
        String piece = label(item, fallbackName);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < plain.length()) {
            int idx = indexOf(plain, i);
            if (idx < 0) {
                out.append(plain.substring(i));
                break;
            }
            if (idx > i) out.append(plain.substring(i, idx));
            out.append(piece);
            i = idx + TOKEN.length();
        }
        return out.toString();
    }

    static int indexOf(String s, int from) {
        int n = s.length();
        for (int i = from; i <= n - 3; i++) {
            if (s.charAt(i) != '[') continue;
            if ((s.charAt(i + 1) == 'i' || s.charAt(i + 1) == 'I') && s.charAt(i + 2) == ']') return i;
        }
        return -1;
    }
}
