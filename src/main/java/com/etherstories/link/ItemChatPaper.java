package com.etherstories.link;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.inventory.ItemStack;

/** 仅 Paper AsyncChatEvent 路径加载，Arclight 不会碰这个类。 */
final class ItemChatPaper {
    private ItemChatPaper() {}

    static Component replace(String plain, ItemStack item) {
        Component piece = itemPart(item);
        Component out = Component.empty();
        int i = 0;
        while (i < plain.length()) {
            int idx = ItemChat.indexOf(plain, i);
            if (idx < 0) {
                out = out.append(legacy(plain.substring(i)));
                break;
            }
            if (idx > i) out = out.append(legacy(plain.substring(i, idx)));
            out = out.append(piece);
            i = idx + ItemChat.TOKEN.length();
        }
        return out;
    }

    static Component legacy(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(ColorUtil.colorize(s == null ? "" : s));
    }

    private static Component itemPart(ItemStack item) {
        String label = ItemChat.label(item, null);
        if (item == null || item.getType().isAir()) return Component.text(label);
        try {
            return Component.text(label).hoverEvent(item.asHoverEvent());
        } catch (Throwable t) {
            return Component.text(label).hoverEvent(HoverEvent.showText(Component.text(ItemCodec.display(item))));
        }
    }
}
