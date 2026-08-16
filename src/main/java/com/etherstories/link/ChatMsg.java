package com.etherstories.link;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Arclight 没有 Adventure，点击/悬停走 Bungee Chat。 */
public final class ChatMsg {

    private ChatMsg() {}

    public static TextComponent text(String s) {
        return new TextComponent(s == null ? "" : s);
    }

    public static TextComponent legacy(String s) {
        BaseComponent[] arr = TextComponent.fromLegacyText(ColorUtil.colorize(s == null ? "" : s));
        if (arr.length == 1 && arr[0] instanceof TextComponent one) return one;
        TextComponent root = new TextComponent("");
        for (BaseComponent c : arr) root.addExtra(c);
        return root;
    }

    public static TextComponent copy(String label, String payload, String hover) {
        TextComponent t = legacy(label);
        t.setClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, payload == null ? "" : payload));
        t.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(hover == null ? "复制" : hover)));
        return t;
    }

    public static TextComponent click(String label, String cmd, String hover) {
        TextComponent t = legacy(label);
        t.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd));
        t.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(hover)));
        return t;
    }

    public static TextComponent suggest(String label, String cmd, String hover) {
        TextComponent t = legacy(label);
        t.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, cmd));
        t.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(hover)));
        return t;
    }

    public static void send(Player p, BaseComponent... parts) {
        p.spigot().sendMessage(parts);
    }

    public static void notice(Player p, String text) {
        p.sendMessage(ColorUtil.colorize("&bESLink &7» &f" + text));
    }

    public static void notice(Player p, BaseComponent... extra) {
        BaseComponent[] all = new BaseComponent[extra.length + 1];
        all[0] = text("ESLink » ");
        System.arraycopy(extra, 0, all, 1, extra.length);
        send(p, all);
    }

    public static BaseComponent itemBody(String plain, ItemStack item, String fallbackName) {
        if (!ItemChat.hasToken(plain)) return legacy(plain);
        TextComponent root = text("");
        String pieceLabel = ItemChat.label(item, fallbackName);
        String hover = (item == null || item.getType().isAir())
                ? (fallbackName == null || fallbackName.isBlank() ? "本服没有此物品" : fallbackName + "\n本服没有此物品")
                : ItemCodec.display(item);
        TextComponent piece = text(pieceLabel);
        piece.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(hover)));
        int i = 0;
        while (i < plain.length()) {
            int idx = ItemChat.indexOf(plain, i);
            if (idx < 0) {
                root.addExtra(legacy(plain.substring(i)));
                break;
            }
            if (idx > i) root.addExtra(legacy(plain.substring(i, idx)));
            root.addExtra(piece.duplicate());
            i = idx + ItemChat.TOKEN.length();
        }
        return root;
    }
}
