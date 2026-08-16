package com.etherstories.link;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/** 不直接引用 Vault 类，没装 Vault 也能启动 */
public final class VaultHook {
    private Object eco;

    public void hook() {
        eco = null;
        try {
            Class<?> econ = Class.forName("net.milkbowl.vault.economy.Economy");
            @SuppressWarnings({"rawtypes", "unchecked"})
            RegisteredServiceProvider rsp = Bukkit.getServicesManager().getRegistration(econ);
            eco = rsp == null ? null : rsp.getProvider();
        } catch (Throwable ignored) {
            eco = null;
        }
    }

    public boolean ok() { return eco != null; }

    public String format(double v) {
        if (eco == null) return String.format("%.2f", v);
        try {
            Object r = Reflect.method(eco.getClass(), "format", double.class).invoke(eco, v);
            return r == null ? String.format("%.2f", v) : r.toString();
        } catch (Throwable t) {
            return String.format("%.2f", v);
        }
    }

    public double bal(Player p) {
        if (eco == null) return 0;
        try {
            Object r = Reflect.method(eco.getClass(), "getBalance", OfflinePlayer.class).invoke(eco, p);
            return r instanceof Number n ? n.doubleValue() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    public String withdraw(Player p, double amount) {
        if (eco == null) return "经济不可用";
        if (amount <= 0) return null;
        try {
            Object r = Reflect.method(eco.getClass(), "withdrawPlayer", OfflinePlayer.class, double.class)
                    .invoke(eco, p, amount);
            boolean ok = Boolean.TRUE.equals(Reflect.method(r.getClass(), "transactionSuccess").invoke(r));
            if (ok) return null;
            Object err = Reflect.method(r.getClass(), "errorMessage").invoke(r);
            return err == null ? "扣款失败" : err.toString();
        } catch (Throwable t) {
            return "扣款失败";
        }
    }

    public void deposit(OfflinePlayer p, double amount) {
        if (eco == null || amount <= 0) return;
        try {
            Reflect.method(eco.getClass(), "depositPlayer", OfflinePlayer.class, double.class)
                    .invoke(eco, p, amount);
        } catch (Throwable ignored) {
        }
    }
}
