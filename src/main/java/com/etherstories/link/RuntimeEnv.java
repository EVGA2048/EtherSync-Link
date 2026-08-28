package com.etherstories.link;

import org.bukkit.Bukkit;

import java.util.Locale;

/** 识别 Paper / Arclight / Forge，物品还原按平台换尝试顺序。 */
public final class RuntimeEnv {

    public enum Kind { PAPER, ARCLIGHT, MOHIST, OTHER }

    private static Kind kind = Kind.OTHER;
    private static boolean forge;
    private static boolean paperApi;
    private static String label = "unknown";

    private RuntimeEnv() {}

    public static void probe() {
        String name = (Bukkit.getName() + " " + Bukkit.getVersion() + " " + Bukkit.getBukkitVersion())
                .toLowerCase(Locale.ROOT);
        paperApi = classExists("io.papermc.paper.event.player.AsyncChatEvent");
        forge = classExists("net.neoforged.neoforge.registries.ForgeRegistries")
                || classExists("net.minecraftforge.registries.ForgeRegistries")
                || classExists("net.neoforged.bus.api.IEventBus");
        if (name.contains("arclight")) kind = Kind.ARCLIGHT;
        else if (name.contains("youer") || name.contains("mohist") || name.contains("catserver")) kind = Kind.MOHIST;
        else if (paperApi && !forge) kind = Kind.PAPER;
        else kind = Kind.OTHER;
        label = Bukkit.getName()
                + (forge ? " + Forge/NeoForge" : "")
                + (paperApi ? " + PaperAPI" : "");
    }

    public static Kind kind() { return kind; }

    public static boolean keepChatSession() {
        return kind == Kind.ARCLIGHT;
    }

    public static boolean hybrid() {
        return kind == Kind.ARCLIGHT || kind == Kind.MOHIST || forge;
    }

    public static boolean forge() { return forge; }

    public static String label() { return label; }

    public static String itemStrategy() {
        if (hybrid()) return "模组物品按注册名还原（NMS / Forge registry）";
        return "原版物品按 Bukkit / Paper 还原";
    }

    private static boolean classExists(String n) {
        try {
            Class.forName(n);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
