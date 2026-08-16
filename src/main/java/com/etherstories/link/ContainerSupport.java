package com.etherstories.link;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 容器传输能不能开，不靠猜，靠启动时真跑一遍往返。
 * Youer / Arclight 上潜影盒和 Create 包裹的组件能力差别很大，
 * 自检不过就整类不放行，宁可留在原地也不要拖垮主线程。
 */
public final class ContainerSupport {

    public enum Mode { AUTO, ON, OFF }

    /** 单个容器在主线程上的重建预算，超了就熔断。 */
    static final long BUILD_BUDGET_MS = 60;
    private static final long MAIN_BUDGET_MS = 2000;

    private static final List<String> NOTES = new CopyOnWriteArrayList<>();
    private static volatile Mode mode = Mode.AUTO;
    private static volatile boolean probed;
    private static volatile boolean skipped;
    private static volatile boolean probing;
    private static final java.util.concurrent.atomic.AtomicBoolean warming =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile boolean genericOk;
    private static volatile boolean packageOk;
    private static volatile boolean packageInstalled;
    private static volatile long probeMs;
    private static volatile String tripped;

    private ContainerSupport() {}

    public static void configure(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        mode = switch (s) {
            case "on", "true", "always" -> Mode.ON;
            case "off", "false", "never" -> Mode.OFF;
            default -> Mode.AUTO;
        };
    }

    public static Mode mode() {
        return mode;
    }

    /**
     * 这个容器能不能拆成内含再重建。不能的话走整包 NMS 快照，
     * 跟普通模组物品同一条路：对面装了同样的模组就收得到，没装就整件退回。
     */
    public static boolean splittable(String itemKey) {
        if (!NestedItems.containerLike(itemKey)) return false;
        if (NestedItems.fluidPackage(itemKey)) return false;
        if (mode == Mode.OFF || tripped != null) return false;
        // 自检就是在测这条路能不能走，不能拿它自己还没算出来的结论去挡它，
        // 否则 genericOk 永远是 false，自检永远失败。
        if (probing) return true;
        if (mode == Mode.ON) return true;
        if (!probed || skipped) return false;
        return NestedItems.packageLike(itemKey) ? packageOk : genericOk;
    }

    /** 容器现在能不能收发。拆不了的走整包，所以只在关掉或熔断时才真拦。 */
    public static boolean allow(String itemKey) {
        if (!NestedItems.containerLike(itemKey)) return true;
        return mode != Mode.OFF && tripped == null;
    }

    /** 自检还没跑完：先别退回，等下一轮。 */
    public static boolean pending(String itemKey) {
        return NestedItems.containerLike(itemKey) && !NestedItems.fluidPackage(itemKey)
                && mode == Mode.AUTO && !probed && !skipped && tripped == null;
    }

    public static void trip(String why) {
        if (tripped != null) return;
        tripped = why;
        NOTES.add("熔断 " + why);
    }

    public static void clearTrip() {
        tripped = null;
        NOTES.add("熔断已复位");
    }

    public static String blockReason(String itemKey) {
        if (mode == Mode.OFF) return "容器传输已在 config 里关闭";
        if (tripped != null) return "容器传输已熔断: " + tripped;
        return "容器传输当前不可用";
    }

    /**
     * 重活（遍历一万多项注册表）先在异步线程做完，主线程只留造 BlockStateMeta 那几毫秒。
     * 以前整个自检都压在主线程上，Arclight 实测 45 秒，玩家全超时掉线。
     */
    public static void probe(ESLinkPlugin plugin) {
        if (!warming.compareAndSet(false, true)) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            long t = System.nanoTime();
            try {
                ItemKeys.warm();
                DataComponents.indexedTypes();
            } catch (Throwable ignored) {
            }
            long ms = (System.nanoTime() - t) / 1_000_000L;
            if (ms > 3000) {
                plugin.getLogger().warning("注册表索引预热 " + ms + "ms，太慢了。"
                        + "它虽然在异步线程，但会抢类加载锁，服务器可能跟着掉 tick。");
            } else if (ms > 500) {
                plugin.getLogger().info("注册表索引预热 " + ms + "ms");
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    probeMain(plugin);
                } finally {
                    warming.set(false);
                }
            });
        });
    }

    private static void probeMain(ESLinkPlugin plugin) {
        java.io.File lock = new java.io.File(plugin.getDataFolder(), "probe.lock");
        if (lock.exists()) {
            probed = true;
            skipped = true;
            genericOk = false;
            packageOk = false;
            NOTES.clear();
            // 只跳过这一次就把锁删掉：既不会反复把服务器带进同一个坑，也不会永久废掉容器。
            lock.delete();
            NOTES.add("上次自检没跑完服务器就没了，本次启动跳过，下次启动会自动重试");
            plugin.getLogger().severe("容器自检上次未完成，本次跳过并停用容器传输。"
                    + "重启后会自动重跑，也可以现在 /link diag retry。");
            return;
        }
        touch(lock);
        long t0 = System.nanoTime();
        NOTES.clear();
        probed = false;
        skipped = false;
        probing = true;
        try {
            ItemKeys.warm();
            NOTES.add("物品索引 " + ItemKeys.indexedItems() + " 项");
            NOTES.add("组件索引 " + DataComponents.indexedTypes() + " 项 · minecraft:container "
                    + (DataComponents.typeLookupWorks() ? "可用" : "缺失"));
            genericOk = roundTrip("minecraft:shulker_box", "潜影盒");
            String pkg = pickPackage();
            packageInstalled = pkg != null;
            packageOk = packageInstalled && roundTrip(pkg, "包裹");
            if (!packageInstalled) NOTES.add("包裹 本服没装，跳过");
        } catch (Throwable t) {
            genericOk = false;
            packageOk = false;
            NOTES.add("自检崩了 " + t.getClass().getSimpleName());
        } finally {
            probing = false;
        }
        probeMs = (System.nanoTime() - t0) / 1_000_000L;
        probed = true;
        lock.delete();
        // 主线程这段只该是几毫秒的物品重建。真超了说明还有重活漏在这边，
        // 宁可停掉拆包也不能让它每次重载都卡一次服。
        if (probeMs > MAIN_BUDGET_MS) {
            genericOk = false;
            packageOk = false;
            trip("自检占用主线程 " + probeMs + "ms");
            NOTES.add("自检太慢已停用拆包，容器改走整包");
        }
        for (String line : NOTES) plugin.getLogger().info("容器自检 · " + line);
        String verdict = "容器自检 " + probeMs + "ms · 潜影盒" + yn(genericOk) + " · 包裹" + yn(packageOk)
                + " · 模式 " + mode;
        if (genericOk || packageOk || mode == Mode.OFF) plugin.getLogger().info(verdict);
        else plugin.getLogger().warning(verdict + "，容器不会收发");
    }

    public static List<String> lines() {
        List<String> out = new ArrayList<>();
        boolean ran = probed && !skipped;
        out.add("模式 " + mode + " · 自检 " + (ran ? probeMs + "ms" : "没跑过"));
        out.add("潜影盒 " + route(genericOk && ran) + " · 包裹 "
                + (packageInstalled || !ran ? route(packageOk && ran) : "本服没装"));
        out.add("拆包=按内含投递，不兼容的退回发送方；整包=对面装了同样模组才收得到");
        out.add(tripped == null ? "熔断 无" : "熔断 " + tripped);
        out.addAll(NOTES);
        return out;
    }

    private static String yn(boolean ok) {
        return ok ? "通过" : "不通过";
    }

    private static String route(boolean split) {
        return split ? "拆包" : "整包";
    }

    /** 只有 buildPacked 跑在主线程，预算就只卡它；编解码在生产里是异步的。 */
    private static boolean roundTrip(String key, String label) {
        try {
            ItemStack box = ItemKeys.create(key, 1);
            if (!ItemKeys.real(box)) {
                NOTES.add(label + " 造不出 " + key);
                return false;
            }
            List<ItemStack> want = probeContents();
            if (want.size() < 2) {
                NOTES.add(label + " 缺测试物品，跳过");
                return false;
            }
            ItemStack filled = NestedItems.fill(box, want);
            List<ItemStack> inside = NestedItems.inners(filled);
            if (inside == null || inside.size() < want.size()) {
                NOTES.add(label + " 装不进 " + key + " · " + NestedItems.componentSummary(filled));
                return false;
            }
            byte[] blob = ItemNbt.save(filled);
            if (blob == null || !ItemNbt.packed(blob)) {
                NOTES.add(label + " 打包失败 " + key + " · " + ItemNbt.kind(blob));
                return false;
            }
            ItemNbt.PackedDecoded decoded = ItemNbt.decodePacked(blob, true);
            if (decoded == null) {
                NOTES.add(label + " 解包失败 " + key + " · " + ItemNbt.kind(blob));
                return false;
            }
            long best = Long.MAX_VALUE;
            for (int round = 0; round < 4; round++) {
                long t = System.nanoTime();
                ItemStack back = ItemNbt.buildPacked(decoded);
                long ns = System.nanoTime() - t;
                List<ItemStack> got = NestedItems.inners(back);
                if (!ItemKeys.real(back) || got == null || got.size() < want.size()) {
                    NOTES.add(label + " 还原丢内含 " + key + " · " + ItemNbt.kind(blob));
                    return false;
                }
                if (round > 0) best = Math.min(best, ns);
            }
            long ms = best / 1_000_000L;
            if (ms > BUILD_BUDGET_MS) {
                NOTES.add(label + " 主线程重建 " + ms + "ms 超预算 " + BUILD_BUDGET_MS + "ms");
                return false;
            }
            NOTES.add(label + " 通过 主线程重建 " + ms + "ms · " + key);
            return true;
        } catch (Throwable t) {
            NOTES.add(label + " 自检异常 " + t.getClass().getSimpleName());
            return false;
        }
    }

    /** Create 纸箱才是真正要拆内含的；流体包裹装的是流体，走普通整包路径。 */
    private static String pickPackage() {
        String cardboard = ItemKeys.firstMatching(
                k -> k.startsWith("create:") && k.contains("cardboard_package"));
        if (cardboard != null) return cardboard;
        return ItemKeys.firstMatching(NestedItems::createPackage);
    }

    private static List<ItemStack> probeContents() {
        List<ItemStack> out = new ArrayList<>();
        ItemStack stone = ItemKeys.create("minecraft:stone", 5);
        ItemStack planks = ItemKeys.create("minecraft:oak_planks", 3);
        if (ItemKeys.real(stone)) out.add(stone);
        if (ItemKeys.real(planks)) out.add(planks);
        return out;
    }

    static void scheduleProbe(ESLinkPlugin plugin) {
        if (mode == Mode.OFF) {
            probed = true;
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> probe(plugin), 40L);
    }

    private static void touch(java.io.File lock) {
        try {
            lock.getParentFile().mkdirs();
            lock.createNewFile();
        } catch (Exception ignored) {
        }
    }
}
