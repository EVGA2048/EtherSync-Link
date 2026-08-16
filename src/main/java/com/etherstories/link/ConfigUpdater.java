package com.etherstories.link;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;

/** 启动/reload：补全新键，不覆盖已有值，升高 config-version */
public final class ConfigUpdater {

    /** 与 config.yml 的 config-version 一起加 */
    public static final int CURRENT_VERSION = 14;

    private ConfigUpdater() {}

    public static void migrate(JavaPlugin plugin) {
        YamlConfiguration jar;
        try {
            jar = YamlConfiguration.loadConfiguration(new InputStreamReader(
                    Objects.requireNonNull(plugin.getResource("config.yml")), StandardCharsets.UTF_8));
        } catch (Exception e) {
            plugin.getLogger().warning("读不到内置 config.yml: " + e.getMessage());
            return;
        }

        FileConfiguration cfg = plugin.getConfig();
        int fileVersion = cfg.getInt("config-version", 0);
        boolean save = false;

        if (cfg.getBoolean("config.auto-fill-missing", true)) {
            int added = fillMissing(cfg, jar, "");
            if (added > 0) {
                save = true;
                plugin.getLogger().info("配置自动补全: +" + added + " 个键（已有值未改）");
            }
            cfg.setDefaults(jar);
            cfg.options().copyDefaults(true);
        }

        if (fileVersion < 5 && "[{code}] ".equals(cfg.getString("chat.prefix"))) {
            cfg.set("chat.prefix", "[{name}] ");
            save = true;
        }

        if (fileVersion < CURRENT_VERSION) {
            cfg.set("config-version", CURRENT_VERSION);
            save = true;
            plugin.getLogger().info("配置版本 v" + fileVersion + " → v" + CURRENT_VERSION);
        }

        if (save) plugin.saveConfig();
    }

    private static int fillMissing(ConfigurationSection target, ConfigurationSection defaults, String path) {
        int added = 0;
        Set<String> keys = defaults.getKeys(false);
        for (String key : keys) {
            String full = path.isEmpty() ? key : path + "." + key;
            if (defaults.isConfigurationSection(key)) {
                ConfigurationSection defChild = defaults.getConfigurationSection(key);
                if (defChild == null) continue;
                if (!target.contains(key)) {
                    ConfigurationSection created = target.createSection(key);
                    added += 1 + fillMissing(created, defChild, full);
                } else if (target.isConfigurationSection(key)) {
                    ConfigurationSection tgtChild = target.getConfigurationSection(key);
                    if (tgtChild != null) added += fillMissing(tgtChild, defChild, full);
                }
            } else if (!target.contains(key)) {
                target.set(key, defaults.get(key));
                added++;
            }
        }
        return added;
    }
}
