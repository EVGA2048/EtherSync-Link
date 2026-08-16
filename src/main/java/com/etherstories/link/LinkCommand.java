package com.etherstories.link;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class LinkCommand implements TabExecutor {
    private final ESLinkPlugin plugin;

    public LinkCommand(ESLinkPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("eslink.admin")) {
                sender.sendMessage(ColorUtil.colorize("&c没有权限"));
                return true;
            }
            boolean ok = plugin.reloadLink();
            sender.sendMessage(ColorUtil.colorize(ok ? "&aESLink 已重载并连上 MySQL" : "&c重载失败，检查 config.yml 的 MySQL"));
            return true;
        }
        if (args.length > 0 && (args[0].equalsIgnoreCase("version") || args[0].equalsIgnoreCase("ver"))) {
            sender.sendMessage(ColorUtil.colorize(
                    "&8[ESLink] &f" + plugin.getDescription().getVersion()
                            + " &8· &7本服 &f" + plugin.serverCode()
                            + " &8" + plugin.serverName()));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("transport")) {
            if (!sender.hasPermission("eslink.admin") && !sender.hasPermission("eslink.super")) {
                sender.sendMessage(ColorUtil.colorize("&c没有权限"));
                return true;
            }
            boolean on;
            if (args.length >= 2 && (args[1].equalsIgnoreCase("off") || args[1].equalsIgnoreCase("stop"))) {
                on = false;
            } else if (args.length >= 2 && (args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("start"))) {
                on = true;
            } else {
                sender.sendMessage(ColorUtil.colorize("&bESLink &7» &f当前传输 "
                        + (plugin.transportEnabled() ? "&a开启" : "&c已急停")
                        + "&f。用法: /link transport on|off"));
                return true;
            }
            plugin.setTransportEnabled(on);
            String msg = on ? "&a传输已开启" : "&c传输已急停（停止一切 chest 收发，退回回收仍照常）";
            plugin.notifyAdmins(msg);
            sender.sendMessage(ColorUtil.colorize("&bESLink &7» &f" + msg));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("component")) {
            if (!sender.hasPermission("eslink.admin") && !sender.hasPermission("eslink.super")) {
                sender.sendMessage(ColorUtil.colorize("&c没有权限"));
                return true;
            }
            if (args.length >= 3 && (args[1].equalsIgnoreCase("block") || args[1].equalsIgnoreCase("ban"))) {
                String id = args[2];
                plugin.blockComponent(id);
                plugin.notifyAdmins("&c已禁用组件 " + id + "，相关收发将被拦截退回");
                sender.sendMessage(ColorUtil.colorize("&bESLink &7» &f已禁用组件 &c" + id));
                return true;
            }
            if (args.length >= 3 && args[1].equalsIgnoreCase("unblock")) {
                String id = args[2];
                plugin.unblockComponent(id);
                plugin.notifyAdmins("&a已解除禁用组件 " + id);
                sender.sendMessage(ColorUtil.colorize("&bESLink &7» &f已解除禁用组件 &a" + id));
                return true;
            }
            java.util.Set<String> ids = plugin.blockedComponentIds();
            if (ids.isEmpty()) {
                sender.sendMessage(ColorUtil.colorize("&bESLink &7» &f当前没有禁用的组件"));
            } else {
                sender.sendMessage(ColorUtil.colorize("&bESLink &7» &f已禁用组件: &c" + String.join("&f, &c", ids)));
            }
            sender.sendMessage(ColorUtil.colorize("&8用法: /link component block|unblock <id>"));
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("玩家用 /link");
            return true;
        }
        if (args.length > 0) {
            String a = args[0].toLowerCase(Locale.ROOT);
            if (a.equals("box") || a.equals("chest") || a.equals("箱") || a.equals("箱子")
                    || a.equals("互通箱") || a.equals("运输箱")) {
                plugin.gui().openChestMenu(p);
                return true;
            }
            if (a.equals("io") || a.equals("redstone") || a.equals("signal") || a.equals("rs")
                    || a.equals("红石") || a.equals("红石控制器")) {
                plugin.gui().openIoMenu(p);
                return true;
            }
            if (a.equals("tx") || a.equals("rx")) {
                plugin.chests().setup(p, a.toUpperCase(Locale.ROOT));
                return true;
            }
            if (a.equals("unlink") || a.equals("unchest") || a.equals("取消")) {
                if (ChestListener.lookingChest(p) != null || ChestListener.sessionChest(plugin, p) != null) {
                    plugin.chests().unlink(p);
                } else {
                    plugin.io().unlink(p);
                }
                return true;
            }
            if (a.equals("settings") || a.equals("设置") || a.equals("config")) {
                plugin.gui().openSettings(p);
                return true;
            }
            if (a.equals("log") || a.equals("logs") || a.equals("日志")) {
                handleLog(p, args);
                return true;
            }
            if (a.equals("diag") || a.equals("诊断")) {
                handleDiag(p, args);
                return true;
            }
            if (a.equals("help") || a.equals("帮助") || a.equals("book") || a.equals("说明书")) {
                GuideBook.open(plugin, p);
                return true;
            }
            if (a.equals("ignore") || a.equals("mute") || a.equals("屏蔽")) {
                handleIgnore(p, args, true);
                return true;
            }
            if (a.equals("unignore") || a.equals("unmute") || a.equals("取消屏蔽")) {
                handleIgnore(p, args, false);
                return true;
            }
            if (a.equals("msg") || a.equals("tell") || a.equals("w") || a.equals("私聊")) {
                if (args.length < 3) {
                    plugin.msg(p, "用法: /link msg 玩家 内容");
                    return true;
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 2; i < args.length; i++) {
                    if (i > 2) sb.append(' ');
                    sb.append(args[i]);
                }
                plugin.chat().whisper(p, args[1], sb.toString());
                return true;
            }
            if (a.equals("chat") || a.equals("聊天")) {
                if (args.length >= 2) {
                    String b = args[1].toLowerCase(Locale.ROOT);
                    if (b.equals("all") || b.equals("global") || b.equals("全部")) plugin.chat().setAll(p, true);
                    else if (b.equals("local") || b.equals("本服")) plugin.chat().setAll(p, false);
                    else plugin.msg(p, "/link chat  |  /link chat local  |  /link chat all");
                } else {
                    plugin.chat().toggle(p);
                }
                return true;
            }
        }
        if (!p.hasPermission("eslink.use")) {
            plugin.msg(p, "&c没有权限");
            return true;
        }
        if (plugin.store() == null || !plugin.store().ready()) {
            plugin.msg(p, "&c数据库未连接。填 plugins/ESLink/config.yml 后 /link reload");
            return true;
        }
        plugin.ensureCore();
        org.bukkit.block.Block look = ChestListener.lookingChest(p);
        if (look == null) {
            org.bukkit.block.Block n = ChestListener.lookingNode(p);
            if (n != null && IoNet.isIoBody(n.getType())) look = n;
        }
        if (look != null) {
            boolean node = (plugin.chests() != null && plugin.chests().cachedAt(
                    look.getWorld().getName(), look.getX(), look.getY(), look.getZ()) != null)
                    || (plugin.io() != null && plugin.io().cachedAt(
                    look.getWorld().getName(), look.getX(), look.getY(), look.getZ()) != null);
            if (node) {
                plugin.gui().openNodeMenu(p, look);
                return true;
            }
        }
        plugin.sessions().of(p).page = Sessions.Page.HOME;
        plugin.gui().openHome(p);
        return true;
    }

    private void handleDiag(Player p, String[] args) {
        if (!p.hasPermission("eslink.admin") && !p.hasPermission("eslink.super")) {
            plugin.msg(p, "&c没有权限");
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("io") || sub.equals("红石")) {
            plugin.msg(p, "&8—— 红石诊断 ——");
            if (!plugin.ioEnabled()) plugin.msg(p, "&7已在 config.yml 关闭（io.enabled: false）");
            else if (plugin.io() == null) plugin.msg(p, "&7红石模块未启动");
            else for (String line : plugin.io().diagLines()) plugin.msg(p, "&7" + line);
            return;
        }
        if (sub.equals("retry") || sub.equals("重试")) {
            ContainerSupport.clearTrip();
            new java.io.File(plugin.getDataFolder(), "probe.lock").delete();
            ContainerSupport.probe(plugin);
            plugin.msg(p, "&a容器自检已开始（后台跑，几秒后再 /link diag 看结果）");
            return;
        }
        plugin.msg(p, "&8—— 容器诊断 " + plugin.getDescription().getVersion() + " ——");
        plugin.msg(p, "&7平台 &f" + RuntimeEnv.label());
        for (String line : ContainerSupport.lines()) plugin.msg(p, "&7" + line);
        for (String line : Compat.lines(plugin)) plugin.msg(p, "&7" + line);
        for (String line : DataComponents.diagLines()) plugin.msg(p, "&7" + line);
        for (String line : ItemNbt.diagLines()) plugin.msg(p, "&7" + line);
        plugin.msg(p, "&8/link diag retry  重跑自检  ·  /link diag io  红石节点");
    }

    private void handleLog(Player p, String[] args) {
        if (!p.hasPermission("eslink.admin") && !p.hasPermission("eslink.super")) {
            plugin.msg(p, "&c没有权限");
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("clear") || sub.equals("清空")) {
            LinkLog.clear();
            plugin.msg(p, "&7日志已清空");
            return;
        }
        if (sub.equals("debug") || sub.equals("详细")) {
            boolean on = LinkLog.toggleDebug(p);
            plugin.msg(p, on ? "&a详细日志已开，收发会打在聊天里。再 /link log 可复制。"
                    : "&7详细日志已关");
            return;
        }
        if (sub.equals("book") || sub.equals("书")) {
            LinkLog.giveBook(p);
            return;
        }
        LinkLog.show(p);
    }

    private void handleIgnore(Player p, String[] args, boolean mute) {
        if (args.length == 1) {
            if (mute) plugin.chat().listIgnores(p);
            else plugin.chat().unignoreAll(p);
            return;
        }
        String kind = args[1].toLowerCase(Locale.ROOT);
        if (!mute && (kind.equals("all") || kind.equals("全部") || kind.equals("clear"))) {
            plugin.chat().unignoreAll(p);
            return;
        }
        if (kind.equals("player") || kind.equals("p") || kind.equals("玩家")) {
            if (args.length < 3) {
                plugin.chat().notice(p, mute ? "用法: /link ignore player 玩家名" : "用法: /link unignore player 玩家名");
                return;
            }
            if (mute) plugin.chat().ignorePlayer(p, args[2]);
            else plugin.chat().unignorePlayer(p, args[2]);
            return;
        }
        if (kind.equals("server") || kind.equals("s") || kind.equals("服")) {
            if (args.length < 3) {
                plugin.chat().notice(p, mute ? "用法: /link ignore server 服务器名" : "用法: /link unignore server 服务器名");
                return;
            }
            if (mute) plugin.chat().ignoreServer(p, args[2]);
            else plugin.chat().unignoreServer(p, args[2]);
            return;
        }
        if (mute) plugin.chat().ignorePlayer(p, args[1]);
        else plugin.chat().unignorePlayer(p, args[1]);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String pfx = args[0].toLowerCase(Locale.ROOT);
            return Stream.of("chest", "互通箱", "io", "unlink", "chat", "msg", "ignore", "unignore", "reload", "version", "help", "settings", "log", "diag", "transport", "component")
                    .filter(s -> s.startsWith(pfx))
                    .toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("msg") || args[0].equalsIgnoreCase("tell")
                || args[0].equalsIgnoreCase("w") || args[0].equals("私聊"))) {
            String pfx = args[1].toLowerCase(Locale.ROOT);
            return org.bukkit.Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(pfx))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("chat")) {
            String pfx = args[1].toLowerCase(Locale.ROOT);
            return Stream.of("local", "all").filter(s -> s.startsWith(pfx)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("log")) {
            String pfx = args[1].toLowerCase(Locale.ROOT);
            return Stream.of("debug", "clear", "book").filter(s -> s.startsWith(pfx)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("diag")) {
            String pfx = args[1].toLowerCase(Locale.ROOT);
            return Stream.of("retry", "io").filter(s -> s.startsWith(pfx)).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("ignore") || args[0].equalsIgnoreCase("mute")
                || args[0].equalsIgnoreCase("unignore") || args[0].equalsIgnoreCase("unmute"))) {
            String pfx = args[1].toLowerCase(Locale.ROOT);
            Stream<String> opts = args[0].toLowerCase(Locale.ROOT).startsWith("un")
                    ? Stream.of("player", "server", "all")
                    : Stream.of("player", "server");
            return opts.filter(s -> s.startsWith(pfx)).toList();
        }
        return List.of();
    }
}
