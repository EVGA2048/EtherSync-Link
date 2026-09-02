package com.etherstories.link;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** 本服已经显示的互通公屏，给 ECOS Web 对齐游戏里那一行。 */
public final class LinkChatShowEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String name;
    private final String text;
    private final String display;
    private final String kind;

    public LinkChatShowEvent(String name, String text, String display, String kind) {
        this.name = name == null ? "" : name;
        this.text = text == null ? "" : text;
        this.display = display == null ? "" : display;
        this.kind = kind == null || kind.isBlank() ? "link" : kind;
    }

    public String name() { return name; }
    public String text() { return text; }
    public String display() { return display; }
    public String kind() { return kind; }

    public static void call(String name, String text, String display, String kind) {
        Bukkit.getPluginManager().callEvent(new LinkChatShowEvent(name, text, display, kind));
    }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
