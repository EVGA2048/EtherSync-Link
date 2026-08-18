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
        // 模组物品的 asHoverEvent() 会把完整快照塞进聊天包，在模组多的服会让客户端报网络协议错误。
        // 这里只用纯文本悬停，跨服数据库里仍保留完整 item_b64 供还原。
        String hover = ItemCodec.display(item);
        try {
            String key = ItemKeys.id(item);
            if (ItemKeys.usable(key)) hover = key;
        } catch (Throwable ignored) {
        }
        return Component.text(label).hoverEvent(HoverEvent.showText(Component.text(hover)));
    }
}
