package com.etherstories.link;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class LinkGui {
    static final int PAGE = 36;
    private final ESLinkPlugin plugin;

    public LinkGui(ESLinkPlugin plugin) { this.plugin = plugin; }

    public void openHome(Player p) {
        Sessions.State st = plugin.sessions().of(p);
        st.page = Sessions.Page.HOME;
        st.awaitingSearch = false;
        st.awaitingPair = false;
        async(() -> {
            List<Models.ServerRow> servers;
            try {
                servers = plugin.store().servers();
            } catch (Exception e) {
                sync(() -> plugin.msg(p, "&c读服务器失败: " + e.getMessage()));
                return;
            }
            List<Models.ServerRow> sv = servers;
            plugin.rememberServers(sv);
            sync(() -> {
                if (!p.isOnline()) return;
                LinkHolder h = new LinkHolder("home");
                Inventory inv = Bukkit.createInventory(h, 54, ColorUtil.colorize("&b&l互通大厅"));
                h.inv = inv;
                fillGlass(inv);
                int slot = 0;
                for (Models.ServerRow s : sv) {
                    if (slot > 8) break;
                    boolean on = s.online(plugin.offlineMs());
                    String title = ESLinkPlugin.prettyName(s.code(), s.name());
                    ItemStack icon = Items.serverMark(s, on, (on ? "&a" : "&8") + title,
                            serverLore(s, on, "点击查看该服市场上的货物"));
                    inv.setItem(slot++, tag("filter", s.code(), 0, icon));
                }
                inv.setItem(20, tag("market", "", 0, Items.named(Material.CHEST, "&e跨服市场",
                        List.of("&7各服玩家上架的货物", "&8左键打开"))));
                inv.setItem(22, tag("sell", "", 0, Items.named(Material.EMERALD, "&a上架手里的货",
                        List.of("&7把主手里的物品放到跨服市场", "&8点一下后，在聊天栏输入单价"))));
                inv.setItem(24, tag("mine", "", 0, Items.named(Material.ENDER_CHEST, "&b我的上架",
                        List.of("&7正在卖的货", "&8下架后物品退回背包"))));
                inv.setItem(29, tag("io", "", 0, Items.named(Material.REDSTONE_LAMP, "&c跨服红石",
                        List.of("&7看准红石灯后点这里，或输入",
                                "&f/link io",
                                "&7灯亮=在线；离线变灰、故障变红",
                                "&8接收灯本身输出 0–15"))));
                inv.setItem(31, tag("help", "", 0, Items.named(Material.OAK_SIGN, "&f跨服运输箱",
                        List.of("&7看准箱子后点这里，或输入",
                                "&f/link chest",
                                "&7中文: /link 互通箱",
                                "&7登记 TX / RX，自动贴牌",
                                "&8TX 与 RX 勿用漏斗对连"))));
                inv.setItem(33, tag("chat", "", 0, Items.named(
                        plugin.chat().isAll(p) ? Material.GOAT_HORN : Material.PAPER,
                        plugin.chat().isAll(p) ? "&a聊天: 全部互通服" : "&f聊天: 仅本服",
                        List.of("&7点一下切换你说话发到哪里",
                                "&7外服消息可以点名字屏蔽",
                                "&7开着全部时，说太快不会传到对面",
                                "&8/link chat"))));
                inv.setItem(35, tag("mynodes", "", 0, Items.named(Material.COMPASS, "&b我的节点",
                        List.of("&7你登记过的互通箱 / 红石",
                                "&7左键看坐标（本服会指指南针）",
                                "&8右键开关消息"))));
                inv.setItem(37, tag("list-alert", "", 0, Items.named(
                        plugin.wantListingAlert(p) ? Material.LIME_DYE : Material.GRAY_DYE,
                        plugin.wantListingAlert(p) ? "&a上架通知: 开" : "&7上架通知: 关",
                        List.of("&7默认关闭，只影响你自己",
                                "&7开了才会收到本服/外服上架广播",
                                "&8点一下切换"))));
                inv.setItem(38, tag("watches", "", 0, Items.named(Material.BELL, "&e节点消息",
                        List.of("&7你订阅过的互通箱 / 红石",
                                "&7出问题会私聊你",
                                "&8点开可关掉"))));
                if (p.hasPermission("eslink.admin")) {
                    inv.setItem(40, tag("settings", "", 0, Items.named(Material.COMPARATOR, "&c互通设置",
                            List.of("&7通知、标识颜色、交易税",
                                    plugin.isSuper(p) ? "&e超级管理可删除错误服务器" : "&8本服管理",
                                    "&8卖家主页仍可禁止上架"))));
                }
                boolean first = !plugin.guideWelcomed(p);
                inv.setItem(49, tag("guide", "", 0, GuideBook.icon(first)));
                p.openInventory(inv);
                if (first) {
                    plugin.markGuideWelcomed(p);
                    plugin.msg(p, "第一次来？点大厅下面那本 &e说明书 &f，或输入 /link help");
                }
            });
        });
    }

    public void openMarket(Player p) {
        Sessions.State st = plugin.sessions().of(p);
        st.page = Sessions.Page.MARKET;
        async(() -> {
            List<Models.ServerRow> servers;
            List<Models.Listing> rows;
            try {
                servers = plugin.store().servers();
                rows = plugin.store().listings(st.serverFilter, st.query, null, st.marketPage * PAGE, PAGE);
            } catch (Exception e) {
                sync(() -> plugin.msg(p, "&c读市场失败: " + e.getMessage()));
                return;
            }
            List<Models.ServerRow> sv = servers;
            List<Models.Listing> ls = rows;
            plugin.rememberServers(sv);
            sync(() -> {
                if (!p.isOnline()) return;
                LinkHolder h = new LinkHolder("market");
                Inventory inv = Bukkit.createInventory(h, 54, ColorUtil.colorize("&e&l跨服市场"));
                h.inv = inv;
                String qShow = (st.query == null || st.query.isBlank()) ? "（全部）" : st.query;
                String filterShow = st.serverFilter == null ? "全部服务器" : plugin.prettyName(st.serverFilter);
                for (int i = 0; i < 54; i++) inv.setItem(i, pane());
                inv.setItem(0, tag("search", "", 0, Items.named(Material.COMPASS, "&b搜索",
                        List.of("&7点一下，关掉界面后在聊天栏打物品名", "&7回车回到这个界面", "&8输入 cancel 取消"))));
                inv.setItem(1, Items.named(Material.NAME_TAG, "&f当前搜索: &e" + qShow,
                        List.of("&7范围: &f" + filterShow, "&8只是显示，点了没反应")));
                if (st.query != null && !st.query.isBlank()) {
                    inv.setItem(2, tag("search-clear", "", 0, Items.named(Material.BARRIER, "&c清除搜索",
                            List.of("&7回到全部货物"))));
                }
                inv.setItem(3, tag("filter", "", 0, Items.named(Material.CHEST,
                        (st.serverFilter == null ? "&a" : "&7") + "全部服务器", List.of("&8不过滤，看所有服"))));
                int sSlot = 4;
                for (Models.ServerRow s : sv) {
                    if (sSlot > 8) break;
                    boolean sel = s.code().equals(st.serverFilter);
                    boolean on = s.online(plugin.offlineMs());
                    String title = ESLinkPlugin.prettyName(s.code(), s.name());
                    inv.setItem(sSlot++, tag("filter", s.code(), 0, Items.serverMark(s, on,
                            (sel ? "&e" : (on ? "&a" : "&8")) + title,
                            serverLore(s, on, "点击只看该服的货"))));
                }

                for (int i = 0; i < PAGE; i++) {
                    if (i < ls.size()) inv.setItem(9 + i, listingIcon(ls.get(i), "&8左键购买  右键看卖家"));
                }
                inv.setItem(45, tag("page", "prev", 0, Items.named(Material.ARROW, "&f上一页",
                        List.of("&8看更早的货"))));
                inv.setItem(46, Items.named(Material.PAPER, "&f第 " + (st.marketPage + 1) + " 页",
                        List.of("&7本页 " + ls.size() + " 件")));
                inv.setItem(47, tag("page", "next", 0, Items.named(Material.ARROW, "&f下一页",
                        List.of("&8看更多"))));
                inv.setItem(49, tag("home", "", 0, Items.named(Material.OAK_DOOR, "&7返回大厅", null)));
                inv.setItem(50, tag("sell", "", 0, Items.named(Material.EMERALD, "&a上架",
                        List.of("&7主手拿着货再点"))));
                inv.setItem(51, tag("mine", "", 0, Items.named(Material.ENDER_CHEST, "&b我的上架",
                        List.of("&7下架退回背包"))));
                p.openInventory(inv);
            });
        });
    }

    public void openMine(Player p) {
        Sessions.State st = plugin.sessions().of(p);
        st.page = Sessions.Page.MINE;
        async(() -> {
            List<Models.Listing> rows;
            try {
                rows = plugin.store().listings(null, null, p.getUniqueId(), 0, PAGE);
            } catch (Exception e) {
                sync(() -> plugin.msg(p, "&c读取失败: " + e.getMessage()));
                return;
            }
            List<Models.Listing> ls = rows;
            sync(() -> {
                if (!p.isOnline()) return;
                LinkHolder h = new LinkHolder("mine");
                Inventory inv = Bukkit.createInventory(h, 54, ColorUtil.colorize("&b&l我的上架"));
                h.inv = inv;
                fillGlass(inv);
                for (int i = 0; i < ls.size() && i < PAGE; i++) {
                    inv.setItem(i, listingIcon(ls.get(i), "&8左键下架  右键改价"));
                }
                inv.setItem(49, tag("home", "", 0, Items.named(Material.OAK_DOOR, "&7返回", null)));
                p.openInventory(inv);
            });
        });
    }

    public void openSeller(Player p, UUID seller) {
        Sessions.State st = plugin.sessions().of(p);
        st.page = Sessions.Page.SELLER;
        st.sellerView = seller;
        async(() -> {
            List<Models.Listing> rows;
            try {
                rows = plugin.store().listings(null, null, seller, 0, PAGE);
            } catch (Exception e) {
                sync(() -> plugin.msg(p, "&c读取失败: " + e.getMessage()));
                return;
            }
            List<Models.Listing> ls = rows;
            sync(() -> {
                if (!p.isOnline()) return;
                LinkHolder h = new LinkHolder("seller");
                Inventory inv = Bukkit.createInventory(h, 54, ColorUtil.colorize("&d&l卖家"));
                h.inv = inv;
                fillGlass(inv);
                String name = ls.isEmpty() ? Bukkit.getOfflinePlayer(seller).getName() : ls.get(0).sellerName();
                if (name == null) name = seller.toString().substring(0, 8);
                String fromCode = ls.isEmpty() ? "?" : ls.get(0).serverCode();
                boolean local = fromCode.equalsIgnoreCase(plugin.serverCode());
                List<String> headLore = new ArrayList<>();
                headLore.add("&7来自 &f" + plugin.prettyName(fromCode));
                headLore.add(local ? "&a本服玩家" : "&7其他服务器");
                if (p.hasPermission("eslink.admin")) headLore.add("&8" + seller);
                inv.setItem(4, tag("noop", "", 0, Items.playerHead(seller, "&f" + name, headLore)));
                for (int i = 0; i < ls.size() && i < 36; i++) {
                    inv.setItem(9 + i, listingIcon(ls.get(i), "&8左键购买  右键看卖家"));
                }
                inv.setItem(45, tag("market", "", 0, Items.named(Material.ARROW, "&7返回市场", null)));
                if (p.hasPermission("eslink.admin") && local) {
                    inv.setItem(52, tag("ban", seller.toString(), 0,
                            Items.named(Material.REDSTONE_BLOCK, "&c禁止该玩家上架", List.of("&7只作用于本服"))));
                    inv.setItem(53, tag("unban", seller.toString(), 0,
                            Items.named(Material.LIME_CONCRETE, "&a解除禁止", List.of("&7恢复上架和放箱"))));
                    inv.setItem(51, tag("wipe", seller.toString(), 0,
                            Items.named(Material.TNT, "&c强制下架其全部", List.of("&7货不会自动退回", "&7人在本服再自己处理"))));
                }
                p.openInventory(inv);
            });
        });
    }

    public void openConfirm(Player p, long listingId) {
        Sessions.State st = plugin.sessions().of(p);
        st.page = Sessions.Page.CONFIRM;
        st.confirmListingId = listingId;
        async(() -> {
            Models.Listing row;
            try {
                row = plugin.store().listing(listingId);
            } catch (Exception e) {
                sync(() -> plugin.msg(p, "&c读取失败"));
                return;
            }
            Models.Listing L = row;
            sync(() -> {
                if (!p.isOnline()) return;
                if (L == null) {
                    plugin.msg(p, "&c这件货没了");
                    openMarket(p);
                    return;
                }
                LinkHolder h = new LinkHolder("confirm");
                Inventory inv = Bukkit.createInventory(h, 27, ColorUtil.colorize("&6&l确认购买"));
                h.inv = inv;
                fillGlass(inv);
                inv.setItem(13, listingIcon(L, "&7确认要买的就是这件"));
                boolean vault = plugin.vault().ok();
                String bal = vault ? plugin.vault().format(plugin.vault().bal(p)) : "本服没有经济插件";
                inv.setItem(11, tag("buy-no", "", 0, Items.named(Material.RED_CONCRETE, "&c取消",
                        List.of("&8回到市场"))));
                double tax = plugin.taxOf(L.price());
                List<String> buyLore = new ArrayList<>();
                buyLore.add("&7价格 &f" + (vault ? plugin.vault().format(L.price()) : String.valueOf(L.price())));
                if (tax > 0) {
                    buyLore.add("&7互通税 &e" + plugin.vault().format(tax) + " &8(" + plugin.taxRateText() + ")");
                    buyLore.add("&7实付 &c" + plugin.vault().format(L.price() + tax));
                }
                buyLore.add("&7你的余额 &f" + bal);
                buyLore.add("&7卖家 &f" + L.sellerName());
                buyLore.add("&7来自 &f" + plugin.prettyName(L.serverCode()));
                inv.setItem(15, tag("buy-yes", "", L.id(), Items.named(Material.LIME_CONCRETE, "&a确认购买", buyLore)));
                p.openInventory(inv);
            });
        });
    }

    public void openPairServers(Player p) {
        Sessions.State st = plugin.sessions().of(p);
        st.page = Sessions.Page.PAIR;
        async(() -> {
            List<Models.ServerRow> servers;
            try {
                servers = plugin.store().servers();
            } catch (Exception e) {
                sync(() -> plugin.msg(p, "&c读服务器失败"));
                return;
            }
            List<Models.ServerRow> sv = servers;
            plugin.rememberServers(sv);
            sync(() -> {
                if (!p.isOnline()) return;
                LinkHolder h = new LinkHolder("pair-server");
                Inventory inv = Bukkit.createInventory(h, 27, ColorUtil.colorize("&3&l选对面的服务器"));
                h.inv = inv;
                fillGlass(inv);
                int i = 0;
                for (Models.ServerRow s : sv) {
                    if (s.code().equalsIgnoreCase(plugin.serverCode())) continue;
                    if (i >= 18) break;
                    boolean on = s.online(plugin.offlineMs());
                    String title = ESLinkPlugin.prettyName(s.code(), s.name());
                    inv.setItem(i++, tag("pair-srv", s.code(), 0, Items.serverMark(s, on,
                            (on ? "&a" : "&8") + title,
                            serverLore(s, on, "选择对端空闲节点"))));
                }
                inv.setItem(22, tag("pair-cancel", "", 0, Items.named(Material.BARRIER, "&c取消配对",
                        List.of("&7箱子还在，以后再连", "&8也可关掉界面，聊天输入对端 UNIT"))));
                st.awaitingPair = true;
                p.openInventory(inv);
                plugin.msg(p, "选对面的服，或聊天输入对端 UNIT（牌子上那串）。cancel 取消。");
            });
        });
    }

    public void openPairChests(Player p, String targetServer) {
        Sessions.State st = plugin.sessions().of(p);
        st.pairTargetServer = targetServer;
        if ("io".equals(st.pairKind)) {
            openPairIo(p, targetServer);
            return;
        }
        String needRole = oppositeRole(st.pairRole);
        if (needRole == null) {
            plugin.msg(p, "&c先登记发送或接收");
            return;
        }
        async(() -> {
            List<Models.ChestRow> idle;
            try {
                idle = plugin.store().idleChests(targetServer, needRole);
            } catch (Exception e) {
                sync(() -> plugin.msg(p, "&c读取空闲节点失败"));
                return;
            }
            List<Models.ChestRow> rows = idle;
            sync(() -> {
                if (!p.isOnline()) return;
                LinkHolder h = new LinkHolder("pair-chest");
                String otherName = plugin.prettyName(targetServer);
                Inventory inv = Bukkit.createInventory(h, 54, ColorUtil.colorize("&3&lSTANDBY " + needRole + " · " + otherName));
                h.inv = inv;
                fillGlass(inv);
                if (rows.isEmpty()) {
                    inv.setItem(22, Items.named(Material.BARRIER, "&c对端无空闲 " + needRole,
                            List.of("&7请对端先 /link chest 登记 " + needRole)));
                }
                for (int i = 0; i < rows.size() && i < 45; i++) {
                    Models.ChestRow c = rows.get(i);
                    inv.setItem(i, tag("pair-pick", "", c.id(), Items.named(Material.CHEST,
                            "&f" + c.role() + "  UNIT " + c.unit(),
                            List.of("&7操作员 &f" + ownerOf(c),
                                    "&7" + c.world() + "  " + c.x() + " " + c.y() + " " + c.z(),
                                    "&8选择配对"))));
                }
                inv.setItem(49, tag("pair-back", "", 0, Items.named(Material.ARROW, "&7返回",
                        List.of("&8重新选择服务器"))));
                p.openInventory(inv);
            });
        });
    }

    public void openPairIo(Player p, String targetServer) {
        Sessions.State st = plugin.sessions().of(p);
        st.pairTargetServer = targetServer;
        String needRole = oppositeRole(st.pairRole);
        if (needRole == null) {
            plugin.msg(p, "&c先登记发送或接收");
            return;
        }
        async(() -> {
            List<Models.IoRow> idle;
            try {
                idle = plugin.store().idleIo(targetServer, needRole);
            } catch (Exception e) {
                sync(() -> plugin.msg(p, "&c读取空闲红石节点失败"));
                return;
            }
            List<Models.IoRow> rows = idle;
            sync(() -> {
                if (!p.isOnline()) return;
                LinkHolder h = new LinkHolder("pair-io");
                String otherName = plugin.prettyName(targetServer);
                Inventory inv = Bukkit.createInventory(h, 54, ColorUtil.colorize("&c&lIO " + needRole + " · " + otherName));
                h.inv = inv;
                fillGlass(inv);
                if (rows.isEmpty()) {
                    inv.setItem(22, Items.named(Material.BARRIER, "&c对端无空闲 IO " + needRole,
                            List.of("&7请对端先 /link io 登记 " + needRole)));
                }
                for (int i = 0; i < rows.size() && i < 45; i++) {
                    Models.IoRow c = rows.get(i);
                    inv.setItem(i, tag("pair-pick", "", c.id(), Items.named(Material.REDSTONE_TORCH,
                            "&fIO " + c.role() + "  UNIT " + c.unit(),
                            List.of("&7操作员 &f" + (c.ownerName() == null || c.ownerName().isBlank() ? "?" : c.ownerName()),
                                    "&7" + c.world() + "  " + c.x() + " " + c.y() + " " + c.z(),
                                    "&8选择配对"))));
                }
                inv.setItem(49, tag("pair-back", "", 0, Items.named(Material.ARROW, "&7返回", null)));
                p.openInventory(inv);
            });
        });
    }

    public void tryPairUnit(Player p, String raw) {
        Sessions.State st = plugin.sessions().of(p);
        String unit = raw == null ? "" : raw.trim().toUpperCase();
        if (unit.length() != 6) {
            plugin.msg(p, "&cUNIT 是 6 位，看牌子。或 cancel");
            return;
        }
        String need = oppositeRole(st.pairRole);
        if (need == null) {
            plugin.msg(p, "&c先登记本端 TX 或 RX");
            return;
        }
        boolean io = "io".equals(st.pairKind);
        async(() -> {
            try {
                int remoteId = 0;
                String remoteServer = null;
                if (io) {
                    for (Models.IoRow n : plugin.store().idleIoRole(need)) {
                        if (unit.equalsIgnoreCase(n.unit())) {
                            remoteId = n.id();
                            remoteServer = n.serverCode();
                            break;
                        }
                    }
                } else {
                    for (Models.ChestRow c : plugin.store().idleChestsRole(need)) {
                        if (unit.equalsIgnoreCase(c.unit())) {
                            remoteId = c.id();
                            remoteServer = c.serverCode();
                            break;
                        }
                    }
                }
                if (remoteId == 0) {
                    sync(() -> plugin.msg(p, "&c没有空闲的 " + need + " UNIT " + unit));
                    return;
                }
                String srv = remoteServer;
                int id = remoteId;
                sync(() -> {
                    st.pairTargetServer = srv;
                    pairRemote(p, id);
                });
            } catch (Exception e) {
                sync(() -> plugin.msg(p, "&c查找失败: " + e.getMessage()));
            }
        });
    }

    public void pairRemote(Player p, int remoteId) {
        Sessions.State st = plugin.sessions().of(p);
        st.awaitingPair = false;
        Integer localId = st.pendingChestId;
        if (localId == null || localId == 0) {
            plugin.msg(p, "&c本端节点丢失，请重新 /link chest 或 /link io");
            p.closeInventory();
            return;
        }
        if (st.pairTargetServer == null || st.pairTargetServer.isBlank()) {
            plugin.msg(p, "&c先选对面的服务器，或输入 UNIT");
            return;
        }
        String pair = plugin.serverCode() + "-" + st.pairTargetServer + "-" + Integer.toHexString(localId);
        boolean io = "io".equals(st.pairKind);
        async(() -> {
            try {
                if (io) {
                    Models.IoRow mine = plugin.store().ioById(localId);
                    if (mine != null && !plugin.canManage(p, mine.owner())) {
                        sync(() -> plugin.msg(p, "&c这是别人的节点"));
                        return;
                    }
                    plugin.store().pairIo(localId, remoteId, pair);
                    Models.IoRow local = plugin.store().ioById(localId);
                    Models.IoRow remote = plugin.store().ioById(remoteId);
                    sync(() -> {
                        plugin.io().refreshSign(local);
                        p.closeInventory();
                        plugin.msg(p, "&a链路已建立  UNIT " + (local == null ? "?" : local.unit())
                                + " > " + (remote == null ? "?" : remote.unit())
                                + "  ·  " + plugin.prettyName(st.pairTargetServer));
                    });
                } else {
                    Models.ChestRow mine = plugin.store().chestById(localId);
                    if (mine != null && !plugin.canManage(p, mine.owner())) {
                        sync(() -> plugin.msg(p, "&c这是别人的节点"));
                        return;
                    }
                    plugin.store().pairChests(localId, remoteId, pair);
                    Models.ChestRow local = plugin.store().chestById(localId);
                    Models.ChestRow remote = plugin.store().chestById(remoteId);
                    sync(() -> {
                        plugin.chests().refreshSign(local);
                        plugin.refreshRxCache();
                        p.closeInventory();
                        plugin.msg(p, "&a链路已建立  UNIT " + (local == null ? "?" : local.unit())
                                + " > " + (remote == null ? "?" : remote.unit())
                                + "  ·  " + plugin.prettyName(st.pairTargetServer));
                    });
                }
            } catch (Exception e) {
                sync(() -> plugin.msg(p, "&c配对失败: " + e.getMessage()));
            }
        });
    }

    public void beginSell(Player p) {
        try {
            if (plugin.store().banned(plugin.serverCode(), p.getUniqueId())) {
                plugin.msg(p, "&c你已被本服禁止上架");
                return;
            }
        } catch (Exception e) {
            plugin.msg(p, "&c检查封禁失败");
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            plugin.msg(p, "&c主手拿着要上架的物品");
            return;
        }
        if (!plugin.tradeEnabled()) {
            plugin.msg(p, "&c本服已关闭互通交易");
            return;
        }
        if (!plugin.allowed(hand)) {
            plugin.msg(p, "&c不在互通白名单: " + Items.itemKey(hand));
            return;
        }
        Sessions.State st = plugin.sessions().of(p);
        st.listItem = hand.clone();
        st.listAmount = hand.getAmount();
        st.awaitingPrice = true;
        st.awaitingSearch = false;
        st.repriceId = 0;
        p.closeInventory();
        plugin.msg(p, "在聊天栏输入单价（数字），或输入 cancel 取消。数量=" + st.listAmount);
    }

    public void finishSell(Player p, double price) {
        Sessions.State st = plugin.sessions().of(p);
        ItemStack item = st.listItem;
        st.awaitingPrice = false;
        st.listItem = null;
        if (item == null) {
            plugin.msg(p, "&c没有待上架物品");
            return;
        }
        if (price < 0) {
            plugin.msg(p, "&c价格无效");
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || !hand.isSimilar(item) || hand.getAmount() < st.listAmount) {
            plugin.msg(p, "&c主手物品对不上了，取消");
            return;
        }
        ItemStack take = hand.clone();
        take.setAmount(st.listAmount);
        hand.setAmount(hand.getAmount() - st.listAmount);
        String b64 = ItemCodec.encode(take);
        String key = Items.itemKey(take);
        String name = ItemCodec.display(take);
        int amt = st.listAmount;
        async(() -> {
            try {
                plugin.store().insertListing(p.getUniqueId(), p.getName(), plugin.serverCode(),
                        key, name, amt, price, b64, NestedItems.csv(take));
                sync(() -> {
                    plugin.alerts().listingLocal(p, name, amt, price);
                    plugin.msg(p, "&a已上架 " + name + " x" + amt);
                    openMine(p);
                });
            } catch (Exception e) {
                sync(() -> {
                    p.getInventory().addItem(take);
                    plugin.msg(p, "&c上架失败，已退回: " + e.getMessage());
                });
            }
        });
    }

    public void doBuy(Player p, long id) {
        async(() -> {
            Models.Listing L;
            try {
                L = plugin.store().listing(id);
            } catch (Exception e) {
                sync(() -> plugin.msg(p, "&c读取失败"));
                return;
            }
            if (L == null) {
                sync(() -> plugin.msg(p, "&c货没了"));
                return;
            }
            if (L.seller().equals(p.getUniqueId())) {
                sync(() -> plugin.msg(p, "&c那是你自己上架的"));
                return;
            }
            Models.Listing row = L;
            sync(() -> {
                if (!plugin.tradeEnabled()) {
                    plugin.msg(p, "&c本服已关闭互通交易");
                    return;
                }
                if (!ItemCodec.known(row.blob(), row.itemKey(), row.nestedKeys())) {
                    plugin.msg(p, "&c本服没有此物品，买不了（" + row.itemKey() + "）");
                    return;
                }
                double tax = plugin.taxOf(row.price());
                double pay = row.price() + tax;
                if (pay > 0) {
                    if (!plugin.vault().ok()) {
                        plugin.msg(p, "&c本服没有 Vault 经济，买不了标价货");
                        return;
                    }
                    String err = plugin.vault().withdraw(p, pay);
                    if (err != null) {
                        plugin.msg(p, "&c" + err);
                        return;
                    }
                }
                async(() -> {
                    try {
                        if (!plugin.store().deleteListing(row.id())) {
                            sync(() -> {
                                if (pay > 0 && plugin.vault().ok()) plugin.vault().deposit(p, pay);
                                plugin.msg(p, "&c被人买走了，已退款");
                            });
                            return;
                        }
                    } catch (Exception e) {
                        sync(() -> {
                            if (pay > 0 && plugin.vault().ok()) plugin.vault().deposit(p, pay);
                            plugin.msg(p, "&c结算失败，已退款");
                        });
                        return;
                    }
                    sync(() -> {
                        if (row.price() > 0 && plugin.vault().ok()) {
                            plugin.vault().deposit(Bukkit.getOfflinePlayer(row.seller()), row.price());
                        }
                        if (tax > 0) plugin.depositTax(tax);
                        ItemStack give = ItemCodec.decode(row.blob(), row.itemKey(), row.amount(), row.nestedKeys());
                        var leftover = p.getInventory().addItem(give);
                        boolean dropped = !leftover.isEmpty();
                        leftover.values().forEach(it -> p.getWorld().dropItemNaturally(p.getLocation(), it));
                        plugin.msg(p, "&a买到 " + row.itemName() + " x" + row.amount()
                                + (tax > 0 ? " &7(含税 " + plugin.vault().format(tax) + ")" : ""));
                        if (dropped) plugin.msg(p, "&e背包满了，多的掉在脚下");
                        openMarket(p);
                    });
                });
            });
        });
    }

    public void unlist(Player p, long id) {
        async(() -> {
            Models.Listing L;
            try {
                L = plugin.store().listing(id);
                if (L == null) {
                    sync(() -> plugin.msg(p, "&c没了"));
                    return;
                }
                if (!L.seller().equals(p.getUniqueId()) && !p.hasPermission("eslink.admin")) {
                    sync(() -> plugin.msg(p, "&c不是你的货"));
                    return;
                }
                if (!L.seller().equals(p.getUniqueId()) && !L.serverCode().equalsIgnoreCase(plugin.serverCode())) {
                    sync(() -> plugin.msg(p, "&c只能管本服成员"));
                    return;
                }
                if (!plugin.store().deleteListing(id)) {
                    sync(() -> plugin.msg(p, "&c下架失败"));
                    return;
                }
            } catch (Exception e) {
                sync(() -> plugin.msg(p, "&c下架失败"));
                return;
            }
            Models.Listing row = L;
            sync(() -> {
                if (row.seller().equals(p.getUniqueId())) {
                    ItemStack give = ItemCodec.decode(row.blob(), row.itemKey(), row.amount(), row.nestedKeys());
                    var leftover = p.getInventory().addItem(give);
                    boolean dropped = !leftover.isEmpty();
                    leftover.values().forEach(it -> p.getWorld().dropItemNaturally(p.getLocation(), it));
                    plugin.msg(p, "&a已下架并退回");
                    if (dropped) plugin.msg(p, "&e背包满了，多的掉在脚下");
                    openMine(p);
                } else {
                    plugin.msg(p, "&a已强制下架（货不退给对方）");
                    openSeller(p, row.seller());
                }
            });
        });
    }

    public void beginReprice(Player p, long id) {
        if (id <= 0) return;
        Sessions.State st = plugin.sessions().of(p);
        st.repriceId = id;
        st.awaitingPrice = true;
        st.awaitingSearch = false;
        st.listItem = null;
        p.closeInventory();
        plugin.msg(p, "在聊天栏输入新单价（数字），或输入 cancel 取消。");
    }

    public void finishReprice(Player p, double price) {
        Sessions.State st = plugin.sessions().of(p);
        long id = st.repriceId;
        st.repriceId = 0;
        st.awaitingPrice = false;
        if (id <= 0) {
            plugin.msg(p, "&c没有待改价的货");
            return;
        }
        if (price < 0) {
            plugin.msg(p, "&c价格无效");
            return;
        }
        async(() -> {
            try {
                Models.Listing L = plugin.store().listing(id);
                if (L == null) {
                    sync(() -> plugin.msg(p, "&c这件货没了"));
                    return;
                }
                if (!L.seller().equals(p.getUniqueId()) && !p.hasPermission("eslink.admin")) {
                    sync(() -> plugin.msg(p, "&c不是你的货"));
                    return;
                }
                if (!L.seller().equals(p.getUniqueId()) && !L.serverCode().equalsIgnoreCase(plugin.serverCode())) {
                    sync(() -> plugin.msg(p, "&c只能管本服成员"));
                    return;
                }
                plugin.store().setListingPrice(id, price);
                sync(() -> {
                    plugin.msg(p, "&a已改价为 " + (plugin.vault().ok() ? plugin.vault().format(price) : String.valueOf(price)));
                    openMine(p);
                });
            } catch (Exception e) {
                sync(() -> plugin.msg(p, "&c改价失败"));
            }
        });
    }

    public void openChestMenu(Player p) {
        org.bukkit.block.Block b = ChestListener.lookingChest(p);
        if (b == null) b = ChestListener.sessionChest(plugin, p);
        if (b == null) {
            plugin.msg(p, "请看准箱子或牌子，再输入 /link chest。蹲下左键牌子也可打开。");
            return;
        }
        openNodeMenu(p, b);
    }

    public void openIoMenu(Player p) {
        org.bukkit.block.Block b = ChestListener.lookingNode(p);
        if (b == null) b = ChestListener.sessionNode(plugin, p);
        if (b == null) {
            plugin.msg(p, "请看准控制器方块或牌子，再输入 /link io。蹲下左键牌子也可打开。");
            return;
        }
        if (ChestListener.chestLike(b)) {
            plugin.msg(p, "&c箱子请用 /link chest。");
            return;
        }
        openNodeMenu(p, b);
    }

    public void openNodeMenu(Player p, org.bukkit.block.Block node) {
        if (!p.hasPermission("eslink.chest")) {
            plugin.msg(p, "&c没有权限");
            return;
        }
        if (plugin.store() == null || !plugin.store().ready()) {
            plugin.msg(p, "&c数据库未连接");
            return;
        }
        if (node == null) return;
        ChestListener.rememberLook(plugin, p, node);
        boolean isChest = ChestListener.chestLike(node);
        Sessions.State st = plugin.sessions().of(p);
        st.page = Sessions.Page.CHEST;
        st.pairKind = isChest ? "chest" : "io";
        String world = node.getWorld().getName();
        int x = node.getX(), y = node.getY(), z = node.getZ();
        org.bukkit.block.Block oh = isChest ? ChestListener.otherHalf(node) : null;
        String ow = oh == null ? null : oh.getWorld().getName();
        int ox = oh == null ? 0 : oh.getX();
        int oy = oh == null ? 0 : oh.getY();
        int oz = oh == null ? 0 : oh.getZ();
        async(() -> {
            Models.ChestRow chest = null;
            Models.IoRow io = null;
            boolean watching = false;
            try {
                if (isChest) {
                    chest = plugin.store().chestAt(plugin.serverCode(), world, x, y, z);
                    if (chest == null && ow != null)
                        chest = plugin.store().chestAt(plugin.serverCode(), ow, ox, oy, oz);
                    if (chest != null) watching = plugin.store().watching(p.getUniqueId(), "chest", chest.id());
                } else {
                    io = plugin.store().ioAt(plugin.serverCode(), world, x, y, z);
                    if (io != null) watching = plugin.store().watching(p.getUniqueId(), "io", io.id());
                }
            } catch (Exception e) {
                sync(() -> plugin.msg(p, "&c读取节点失败"));
                return;
            }
            Models.ChestRow c = chest;
            Models.IoRow n = io;
            boolean w = watching;
            sync(() -> drawNodeMenu(p, node, isChest, c, n, w));
        });
    }

    private void drawNodeMenu(Player p, org.bukkit.block.Block node, boolean isChest,
                              Models.ChestRow chest, Models.IoRow io, boolean watching) {
        if (!p.isOnline()) return;
        Sessions.State st = plugin.sessions().of(p);
        boolean registered = chest != null || io != null;
        String unit = chest != null ? chest.unit() : (io != null ? io.unit() : "——");
        String role = chest != null ? chest.role() : (io != null ? io.role() : "");
        String peer = chest != null ? chest.peerUnit() : (io != null ? io.peerUnit() : "");
        String status = chest != null ? chest.status() : (io != null ? io.status() : "idle");
        String owner = chest != null ? ownerOf(chest) : (io != null ? ioOwner(io) : p.getName());
        if (registered) {
            st.pendingChestId = chest != null ? chest.id() : io.id();
            st.pairRole = role;
        }
        LinkHolder h = new LinkHolder("node");
        Inventory inv = Bukkit.createInventory(h, 27, ColorUtil.colorize("&3&l互通节点  " + unit));
        h.inv = inv;
        fillGlass(inv);
        String pos = node.getWorld().getName() + "  " + node.getX() + " " + node.getY() + " " + node.getZ();
        String kind = isChest ? "互通箱" : "互通红石控制器";
        List<String> info = new ArrayList<>();
        info.add("&7类型 &f" + kind);
        info.add("&7编号 &f" + unit + (peer.isBlank() ? "" : " &7> &e" + peer));
        info.add("&7方向 &f" + (role.isBlank() ? "未登记" : ESLinkPlugin.roleCn(role)));
        info.add("&7状态 &f" + NodeSigns.label(status));
        if (!isChest && io != null) {
            info.add("&7逻辑 &f" + NodeSigns.logicCn(io.logic())
                    + "  &7输出 &f" + NodeSigns.mapLogic(io.logic(), true, io.peerLevel()));
            info.add("&8灯亮=对端在线；离线灯灭且输出 0");
        }
        info.add("&7操作员 &f" + owner);
        if (isChest && chest != null && "TX".equals(role)) {
            String f = chest.itemFilter();
            info.add("&7过滤 &f" + (f.isBlank() ? "全部" : f));
            info.add("&7回退 &f" + (chest.bounceId() <= 0 ? "&c未绑定（点木桶绑定）" : "UNIT 已绑"));
        }
        info.add("&8" + pos);
        inv.setItem(4, Items.named(isChest ? Material.CHEST : Material.REDSTONE_LAMP, "&f" + kind,
                info));
        java.util.UUID ownerId = chest != null ? chest.owner() : (io != null ? io.owner() : null);
        boolean manage = !registered || plugin.canManage(p, ownerId);
        if (manage && !"BK".equals(role)) {
            inv.setItem(11, tag("node-tx", "", 0, Items.named(Material.HOPPER,
                    "TX".equals(role) ? "&a发送端 &7(当前)" : "&f设为发送端",
                    isChest ? List.of("&7物品单向输出", "&7允许漏斗注入")
                            : List.of("&7红石灯读取带电 0–15", "&7灯亮=已配对，离线变灰"))));
            if (!isChest && (io == null || "RX".equals(io.role()))) {
                String logic = io == null ? "normal" : io.logic();
                inv.setItem(13, tag("node-logic", "", io == null ? 0 : io.id(), Items.named(Material.COMPARATOR,
                        "&e接收逻辑: " + NodeSigns.logicCn(logic),
                        List.of("&7正常  对端 0–15 → 本端 0–15",
                                "&7反向  对端 0–15 → 本端 15–0",
                                "&7满信号  对端 1–15 → 本端 15",
                                "&8离线 / 未加载仍输出 0",
                                "&8点击切换"))));
            }
            inv.setItem(15, tag("node-rx", "", 0, Items.named(Material.DROPPER,
                    "RX".equals(role) ? "&b接收端 &7(当前)" : "&f设为接收端",
                    isChest ? List.of("&7物品单向输入", "&7禁止人工放入")
                            : List.of("&7灯亮=对端在线，离线变灰", "&7灯本身像红石块，输出 0–15"))));
        }
        if (registered) {
            int nid = chest != null ? chest.id() : io.id();
            String kindKey = isChest ? "chest" : "io";
            boolean paused = "paused".equals(status);
            if (manage) {
                inv.setItem(19, tag("node-pair", "", 0, Items.named(Material.ENDER_PEARL,
                        peer.isBlank() ? "&d配对对端" : "&d重新配对",
                        List.of(peer.isBlank() ? "&7尚未配对" : "&7对端 " + peer,
                                "&8已配对也可换对端，旧对端会松开"))));
                inv.setItem(20, tag("node-pause", kindKey, nid, Items.named(
                        paused ? Material.LIME_DYE : Material.ORANGE_DYE,
                        paused ? "&a恢复传输" : "&6暂停传输",
                        isChest ? List.of("&7暂停后发送端不再抽货", "&7接收端不再进货")
                                : List.of("&7暂停后输出 0", "&8再点恢复"))));
                inv.setItem(21, tag("node-sign", "", 0, Items.named(Material.OAK_SIGN, "&f补牌子",
                        List.of("&7左键拆牌不会拆节点", "&8点这里重新贴牌"))));
                if (isChest && "RX".equals(role)) {
                    inv.setItem(23, tag("node-clear", "", nid, Items.named(Material.BARRIER, "&e清屏障",
                            List.of("&7清掉本箱「无此物品」标记", "&8不会动正常货物"))));
                }
                if (isChest && "TX".equals(role) && chest != null) {
                    String f = chest.itemFilter();
                    inv.setItem(23, tag("node-bounce", "", chest.id(), Items.named(Material.BARREL,
                            chest.bounceId() <= 0 ? "&e绑定回退箱" : "&a回退箱已绑定",
                            List.of(chest.bounceId() <= 0 ? "&c不绑不能发货" : "&7点此换绑另一口",
                                    "&7对不上的货物退到这里",
                                    "&7回退箱满了发送会停",
                                    "&8点了会关菜单，再对着空箱子右键"))));
                    inv.setItem(24, tag("node-filter", "", chest.id(), Items.named(Material.HOPPER,
                            f.isBlank() ? "&f过滤: 全部" : "&e过滤: " + f,
                            List.of("&7空手点=不过滤",
                                    "&7手里拿物品点=只送这种",
                                    "&7蹲下点=只送该模组",
                                    "&8例如 create 或 create:xxx"))));
                }
                boolean confirm = (kindKey + ":" + nid).equals(st.pendingUnlink);
                inv.setItem(22, tag("node-unlink", "", 0, Items.named(Material.BARRIER,
                        confirm ? "&c再点一次确认拆除" : "&c拆除本节点",
                        List.of(confirm ? "&c登记会删掉，对端松开" : "&7清除牌子与登记",
                                "&8需要点两次"))));
            } else {
                inv.setItem(22, Items.named(Material.BARRIER, "&c这是别人的节点",
                        List.of("&7只能看，不能改/拆", "&8操作员: " + owner)));
            }
            inv.setItem(25, tag("node-watch", isChest ? "chest" : "io", nid, Items.named(
                    watching ? Material.BELL : Material.GRAY_DYE,
                    watching ? "&a接收此" + kind + "的信息: 开" : "&7接收此" + kind + "的信息: 关",
                    List.of("&7无此物品 / 满载时私聊你",
                            "&7对端订阅的人也会收到",
                            "&8大厅「节点消息」可统一关掉"))));
        } else {
            inv.setItem(22, Items.named(Material.MAP, "&7未登记",
                    List.of("&7先选发送或接收")));
        }
        p.openInventory(inv);
    }

    private static String oppositeRole(String role) {
        if ("TX".equals(role)) return "RX";
        if ("RX".equals(role)) return "TX";
        return null;
    }

    private static String ioOwner(Models.IoRow n) {
        if (n.ownerName() != null && !n.ownerName().isBlank()) return n.ownerName();
        return "?";
    }

    public void openMyNodes(Player p) {
        Sessions.State st = plugin.sessions().of(p);
        st.page = Sessions.Page.NODES;
        async(() -> {
            List<Models.ChestRow> chests;
            List<Models.IoRow> ios;
            try {
                chests = plugin.store().chestsOf(p.getUniqueId());
                ios = plugin.store().ioOf(p.getUniqueId());
            } catch (Exception e) {
                sync(() -> plugin.msg(p, "&c读取节点失败"));
                return;
            }
            List<Models.ChestRow> cs = chests;
            List<Models.IoRow> ns = ios;
            sync(() -> {
                if (!p.isOnline()) return;
                LinkHolder h = new LinkHolder("mynodes");
                Inventory inv = Bukkit.createInventory(h, 54, ColorUtil.colorize("&b&l我的节点"));
                h.inv = inv;
                fillGlass(inv);
                int slot = 0;
                for (Models.ChestRow c : cs) {
                    if (slot >= 45) break;
                    boolean here = c.serverCode().equalsIgnoreCase(plugin.serverCode());
                    List<String> lore = new ArrayList<>();
                    lore.add("&7互通箱  " + ESLinkPlugin.roleCn(c.role()));
                    lore.add("&7状态 &f" + NodeSigns.label(c.status()));
                    if (!c.peerUnit().isBlank()) lore.add("&7对端 &e" + c.peerUnit());
                    lore.add("&7" + plugin.prettyName(c.serverCode()) + "  " + c.world());
                    lore.add("&8" + c.x() + " " + c.y() + " " + c.z());
                    lore.add(here ? "&8左键指向  右键开关消息" : "&8外服，左键看坐标  右键开关消息");
                    inv.setItem(slot++, tag("my-node", "chest", c.id(), Items.named(
                            Material.CHEST, (NodeSigns.trouble(c.status()) ? "&c" : "&f") + "互通箱  UNIT " + c.unit(),
                            lore)));
                }
                for (Models.IoRow n : ns) {
                    if (slot >= 45) break;
                    boolean here = n.serverCode().equalsIgnoreCase(plugin.serverCode());
                    boolean down = n.pairCode() != null && !n.pairCode().isBlank()
                            && plugin.io() != null && !plugin.io().isLive(n);
                    String stLabel = down ? "离线" : NodeSigns.label(n.status());
                    List<String> lore = new ArrayList<>();
                    lore.add("&7红石  " + ESLinkPlugin.roleCn(n.role()));
                    lore.add("&7状态 &f" + stLabel);
                    if (!n.peerUnit().isBlank()) lore.add("&7对端 &e" + n.peerUnit());
                    lore.add("&7" + plugin.prettyName(n.serverCode()) + "  " + n.world());
                    lore.add("&8" + n.x() + " " + n.y() + " " + n.z());
                    lore.add(here ? "&8左键指向  右键开关消息" : "&8外服，左键看坐标  右键开关消息");
                    inv.setItem(slot++, tag("my-node", "io", n.id(), Items.named(
                            Material.REDSTONE_LAMP, ((down || NodeSigns.trouble(n.status())) ? "&c" : "&f")
                                    + "红石  UNIT " + n.unit(),
                            lore)));
                }
                if (slot == 0) {
                    inv.setItem(22, Items.named(Material.BARRIER, "&7还没有节点",
                            List.of("&7看准箱子 /link chest", "&7看准红石灯 /link io")));
                }
                inv.setItem(49, tag("home", "", 0, Items.named(Material.OAK_DOOR, "&7返回大厅", null)));
                p.openInventory(inv);
            });
        });
    }

    public void pingNode(Player p, String kind, int id) {
        async(() -> {
            try {
                String world;
                int x, y, z;
                String unit, server, label;
                if ("io".equals(kind)) {
                    Models.IoRow n = plugin.store().ioById(id);
                    if (n == null) {
                        sync(() -> plugin.msg(p, "&c节点没了"));
                        return;
                    }
                    world = n.world();
                    x = n.x();
                    y = n.y();
                    z = n.z();
                    unit = n.unit();
                    server = n.serverCode();
                    label = "红石";
                } else {
                    Models.ChestRow c = plugin.store().chestById(id);
                    if (c == null) {
                        sync(() -> plugin.msg(p, "&c节点没了"));
                        return;
                    }
                    world = c.world();
                    x = c.x();
                    y = c.y();
                    z = c.z();
                    unit = c.unit();
                    server = c.serverCode();
                    label = "互通箱";
                }
                String w = world;
                int xx = x, yy = y, zz = z;
                String u = unit, sv = server, lb = label;
                sync(() -> {
                    plugin.msg(p, lb + " UNIT " + u + "  &7" + plugin.prettyName(sv)
                            + "  " + w + " " + xx + " " + yy + " " + zz);
                    if (!sv.equalsIgnoreCase(plugin.serverCode())) return;
                    org.bukkit.World bw = Bukkit.getWorld(w);
                    if (bw == null) {
                        plugin.msg(p, "&7本服没有这个世界");
                        return;
                    }
                    var loc = new org.bukkit.Location(bw, xx + 0.5, yy, zz + 0.5);
                    p.setCompassTarget(loc);
                    ChestListener.rememberLook(plugin, p, bw.getBlockAt(xx, yy, zz));
                    plugin.msg(p, "&7指南针已指向（原版指南针）");
                });
            } catch (Exception e) {
                sync(() -> plugin.msg(p, "&c读取失败"));
            }
        });
    }

    public void openWatches(Player p) {
        Sessions.State st = plugin.sessions().of(p);
        st.page = Sessions.Page.WATCH;
        async(() -> {
            List<Models.WatchRow> rows;
            try {
                rows = plugin.store().watchesOf(p.getUniqueId());
            } catch (Exception e) {
                sync(() -> plugin.msg(p, "&c读取订阅失败"));
                return;
            }
            List<Models.WatchRow> ls = rows;
            sync(() -> {
                if (!p.isOnline()) return;
                LinkHolder h = new LinkHolder("watches");
                Inventory inv = Bukkit.createInventory(h, 54, ColorUtil.colorize("&e&l节点消息"));
                h.inv = inv;
                fillGlass(inv);
                if (ls.isEmpty()) {
                    inv.setItem(22, Items.named(Material.BARRIER, "&7还没有订阅",
                            List.of("&7在互通箱 / 红石菜单里", "&7打开「接收此节点的信息」")));
                }
                for (int i = 0; i < ls.size() && i < 45; i++) {
                    Models.WatchRow w = ls.get(i);
                    boolean chest = !"io".equals(w.kind());
                    String kind = chest ? "互通箱" : "互通红石控制器";
                    boolean gone = w.serverCode() == null || w.serverCode().isBlank();
                    List<String> lore = new ArrayList<>();
                    lore.add("&7类型 &f" + kind);
                    lore.add("&7方向 &f" + (w.role().isBlank() ? "—" : ESLinkPlugin.roleCn(w.role())));
                    lore.add(gone ? "&8节点已拆除" : "&7所在 &f" + plugin.prettyName(w.serverCode()));
                    if (!gone) lore.add("&7状态 &f" + NodeSigns.label(w.status()));
                    lore.add("&8点击关闭这条消息");
                    inv.setItem(i, tag("watch-off", w.kind(), w.nodeId(), Items.named(
                            chest ? Material.CHEST : Material.REDSTONE_LAMP,
                            (gone ? "&8" : "&f") + kind + "  UNIT " + w.unit(),
                            lore)));
                }
                inv.setItem(49, tag("home", "", 0, Items.named(Material.OAK_DOOR, "&7返回大厅", null)));
                p.openInventory(inv);
            });
        });
    }

    public void openSettings(Player p) {
        if (!p.hasPermission("eslink.admin")) {
            plugin.msg(p, "&c没有权限");
            return;
        }
        Sessions.State st = plugin.sessions().of(p);
        st.page = Sessions.Page.SETTINGS;
        LinkHolder h = new LinkHolder("settings");
        Inventory inv = Bukkit.createInventory(h, 45, ColorUtil.colorize("&c&l互通设置"));
        h.inv = inv;
        fillGlass(inv);
        inv.setItem(4, Items.named(Material.PAPER, "&fESLink &e" + plugin.getDescription().getVersion(),
                List.of("&7本服 &f" + plugin.serverCode() + " &8" + plugin.serverName(),
                        "&8/link version")));
        inv.setItem(10, tag("tog", "alerts.listing", 0, Items.named(
                plugin.alertLocalListing() ? Material.LIME_DYE : Material.GRAY_DYE,
                plugin.alertLocalListing() ? "&a本服上架通知: 开" : "&7本服上架通知: 关",
                List.of("&7总闸。开了也只发给", "&7自己打开「上架通知」的人", "&8点击开关"))));
        inv.setItem(11, tag("tog", "alerts.listing-remote", 0, Items.named(
                plugin.alertRemoteListing() ? Material.LIME_DYE : Material.GRAY_DYE,
                plugin.alertRemoteListing() ? "&a外服上架通知: 开" : "&7外服上架通知: 关",
                List.of("&7总闸。开了也只发给", "&7自己打开「上架通知」的人", "&8点击开关"))));
        inv.setItem(12, tag("tog", "alerts.chest-admin", 0, Items.named(
                plugin.alertChestAdmin() ? Material.LIME_DYE : Material.GRAY_DYE,
                plugin.alertChestAdmin() ? "&a运输箱通知: 开" : "&7运输箱通知: 关",
                List.of("&7有人创建运输箱时通知本服管理", "&8点击开关"))));
        inv.setItem(14, tag("trade-tog", "", 0, Items.named(
                plugin.tradeEnabled() ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                plugin.tradeEnabled() ? "&a互通交易: 开" : "&c互通交易: 关",
                List.of("&7关闭后本服不能上架、不能买"))));
        inv.setItem(15, tag("tax-down", "", 0, Items.named(Material.RED_CONCRETE, "&c税率 -1%",
                List.of("&7当前 &f" + plugin.taxRateText()))));
        inv.setItem(16, Items.named(Material.GOLD_INGOT, "&e互通税率 &f" + plugin.taxRateText(),
                List.of("&7买家多付，卖家仍收标价", "&7税进本服配置的 sink 账户")));
        inv.setItem(17, tag("tax-up", "", 0, Items.named(Material.LIME_CONCRETE, "&a税率 +1%",
                List.of("&7当前 &f" + plugin.taxRateText()))));
        boolean concrete = plugin.serverIcon().contains("CONCRETE");
        inv.setItem(28, tag("colors", "", 0, Items.named(
                Items.serverMat(plugin.serverColor(), plugin.serverIcon()),
                "&f标识颜色: &e" + Items.colorCn(plugin.serverColor()),
                List.of("&7大厅里代表本服的方块", "&8点击选颜色"))));
        inv.setItem(29, tag("shape", "", 0, Items.named(
                concrete ? Material.WHITE_CONCRETE : Material.WHITE_TERRACOTTA,
                concrete ? "&f材质: 混凝土" : "&f材质: 陶瓦",
                List.of("&7不用玩家头，网不好也能看见", "&8点击切换"))));
        inv.setItem(32, tag("admin-nodes", "", 0, Items.named(Material.MAP, "&e本服节点",
                List.of("&7查看本服全部互通箱 / 红石", "&7方块没了的可以清掉"))));
        if (plugin.isSuper(p)) {
            inv.setItem(31, tag("servers", "", 0, Items.named(Material.BARRIER, "&c管理服务器列表",
                    List.of("&7删除调试残留或不存在的服", "&8不会删本服"))));
        }
        inv.setItem(40, tag("home", "", 0, Items.named(Material.OAK_DOOR, "&7返回大厅", null)));
        p.openInventory(inv);
    }

    public void openAdminNodes(Player p) {
        if (!p.hasPermission("eslink.admin")) {
            plugin.msg(p, "&c没有权限");
            return;
        }
        Sessions.State st = plugin.sessions().of(p);
        st.page = Sessions.Page.ADMIN_NODES;
        async(() -> {
            List<Models.ChestRow> chests;
            List<Models.IoRow> ios;
            try {
                chests = plugin.store().chestsOn(plugin.serverCode());
                ios = plugin.store().ioOn(plugin.serverCode());
            } catch (Exception e) {
                sync(() -> plugin.msg(p, "&c读取节点失败"));
                return;
            }
            List<Models.ChestRow> cs = chests;
            List<Models.IoRow> ns = ios;
            sync(() -> {
                if (!p.isOnline()) return;
                LinkHolder h = new LinkHolder("admin-nodes");
                Inventory inv = Bukkit.createInventory(h, 54, ColorUtil.colorize("&c&l本服节点"));
                h.inv = inv;
                fillGlass(inv);
                int slot = 0;
                int ghosts = 0;
                for (Models.ChestRow c : cs) {
                    if (slot >= 45) break;
                    boolean ghost = ghostChest(c);
                    if (ghost) ghosts++;
                    boolean pending = ("chest:" + c.id()).equalsIgnoreCase(st.pendingDelete);
                    List<String> lore = new ArrayList<>();
                    lore.add("&7互通箱  " + ESLinkPlugin.roleCn(c.role()) + "  UNIT " + c.unit());
                    lore.add("&7操作员 &f" + ownerOf(c));
                    lore.add("&7" + c.world() + "  " + c.x() + " " + c.y() + " " + c.z());
                    lore.add("&7状态 &f" + NodeSigns.label(c.status()));
                    if (!c.itemFilter().isBlank()) lore.add("&7过滤 &f" + c.itemFilter());
                    lore.add(ghost ? "&c方块没了（幽灵）" : "&a方块还在");
                    lore.add(pending ? "&c再点一次确认删除登记" : "&8左键指向  右键删除登记");
                    inv.setItem(slot++, tag("admin-node", "chest", c.id(), Items.named(
                            ghost ? Material.BARRIER : Material.CHEST,
                            (ghost ? "&c" : "&f") + "箱 UNIT " + c.unit() + (ghost ? "  幽灵" : ""),
                            lore)));
                }
                for (Models.IoRow n : ns) {
                    if (slot >= 45) break;
                    boolean ghost = ghostIo(n);
                    if (ghost) ghosts++;
                    boolean pending = ("io:" + n.id()).equalsIgnoreCase(st.pendingDelete);
                    List<String> lore = new ArrayList<>();
                    lore.add("&7红石  " + ESLinkPlugin.roleCn(n.role()) + "  UNIT " + n.unit());
                    lore.add("&7操作员 &f" + ioOwner(n));
                    lore.add("&7" + n.world() + "  " + n.x() + " " + n.y() + " " + n.z());
                    lore.add("&7状态 &f" + NodeSigns.label(n.status()));
                    lore.add(ghost ? "&c方块没了（幽灵）" : "&a方块还在");
                    lore.add(pending ? "&c再点一次确认删除登记" : "&8左键指向  右键删除登记");
                    inv.setItem(slot++, tag("admin-node", "io", n.id(), Items.named(
                            ghost ? Material.BARRIER : Material.REDSTONE_LAMP,
                            (ghost ? "&c" : "&f") + "灯 UNIT " + n.unit() + (ghost ? "  幽灵" : ""),
                            lore)));
                }
                if (slot == 0) {
                    inv.setItem(22, Items.named(Material.BARRIER, "&7本服没有节点", null));
                }
                inv.setItem(45, Items.named(Material.PAPER, "&f共 " + (cs.size() + ns.size()) + " 个",
                        List.of("&7幽灵 " + ghosts + " 个", "&8右键删除登记")));
                inv.setItem(49, tag("settings", "", 0, Items.named(Material.ARROW, "&7返回设置", null)));
                p.openInventory(inv);
            });
        });
    }

    private static boolean ghostChest(Models.ChestRow c) {
        org.bukkit.World w = Bukkit.getWorld(c.world());
        if (w == null) return true;
        return !(w.getBlockAt(c.x(), c.y(), c.z()).getState() instanceof org.bukkit.block.Chest);
    }

    private static boolean ghostIo(Models.IoRow n) {
        org.bukkit.World w = Bukkit.getWorld(n.world());
        if (w == null) return true;
        return !IoNet.isIoBody(w.getBlockAt(n.x(), n.y(), n.z()).getType());
    }

    public void openColors(Player p) {
        Sessions.State st = plugin.sessions().of(p);
        st.page = Sessions.Page.COLORS;
        LinkHolder h = new LinkHolder("colors");
        Inventory inv = Bukkit.createInventory(h, 27, ColorUtil.colorize("&3&l选标识颜色"));
        h.inv = inv;
        fillGlass(inv);
        int i = 0;
        for (String c : Items.COLORS) {
            if (i >= 18) break;
            boolean sel = c.equalsIgnoreCase(plugin.serverColor());
            inv.setItem(i++, tag("set-color", c, 0, Items.named(
                    Items.serverMat(c, plugin.serverIcon()),
                    (sel ? "&a" : "&f") + Items.colorCn(c),
                    List.of(sel ? "&a当前使用" : "&8点击设为本服颜色"))));
        }
        inv.setItem(22, tag("settings", "", 0, Items.named(Material.ARROW, "&7返回设置", null)));
        p.openInventory(inv);
    }

    public void openServers(Player p) {
        if (!plugin.isSuper(p)) {
            plugin.msg(p, "&c需要超级管理权限");
            return;
        }
        Sessions.State st = plugin.sessions().of(p);
        st.page = Sessions.Page.SERVERS;
        async(() -> {
            List<Models.ServerRow> servers;
            try {
                servers = plugin.store().servers();
            } catch (Exception e) {
                sync(() -> plugin.msg(p, "&c读服务器失败"));
                return;
            }
            plugin.rememberServers(servers);
            List<Models.ServerRow> sv = servers;
            sync(() -> {
                if (!p.isOnline()) return;
                LinkHolder h = new LinkHolder("servers");
                Inventory inv = Bukkit.createInventory(h, 54, ColorUtil.colorize("&c&l服务器列表"));
                h.inv = inv;
                fillGlass(inv);
                int slot = 0;
                for (Models.ServerRow s : sv) {
                    if (slot >= 45) break;
                    boolean self = s.code().equalsIgnoreCase(plugin.serverCode());
                    boolean on = s.online(plugin.offlineMs());
                    boolean pending = s.code().equalsIgnoreCase(st.pendingDelete);
                    List<String> lore = new ArrayList<>();
                    String blurb = plugin.prettyBlurb(s);
                    if (!blurb.isEmpty()) lore.add("&7" + blurb);
                    lore.add(on ? "&a在线" : "&8离线（可能是残留）");
                    lore.add("&8内部代号 " + s.code());
                    if (self) lore.add("&7这是本服，不能删");
                    else lore.add(pending ? "&c再点一次确认删除" : "&c点击删除这台记录");
                    inv.setItem(slot++, tag(self ? "noop" : "srv-del", s.code(), 0,
                            Items.serverMark(s, on,
                                    (pending ? "&c" : (on ? "&a" : "&8")) + ESLinkPlugin.prettyName(s.code(), s.name()),
                                    lore)));
                }
                inv.setItem(49, tag("settings", "", 0, Items.named(Material.ARROW, "&7返回设置", null)));
                p.openInventory(inv);
            });
        });
    }

    private static String ownerOf(Models.ChestRow c) {
        if (c.ownerName() != null && !c.ownerName().isBlank()) return c.ownerName();
        if (c.owner() == null) return "?";
        String n = Bukkit.getOfflinePlayer(c.owner()).getName();
        return n == null ? c.owner().toString().substring(0, 8) : n;
    }

    private List<String> serverLore(Models.ServerRow s, boolean on, String clickHint) {
        List<String> lore = new ArrayList<>();
        String blurb = plugin.prettyBlurb(s);
        if (!blurb.isEmpty()) lore.add("&7" + blurb);
        lore.add(on ? "&a在线" : "&8离线");
        if (s.code() != null && s.code().equalsIgnoreCase(plugin.serverCode())) lore.add("&f本服");
        if (clickHint != null) lore.add("&8" + clickHint);
        return lore;
    }

    private ItemStack listingIcon(Models.Listing L, String hint) {
        ItemStack icon = ItemCodec.icon(L.blob(), L.itemKey(), L.itemName(), L.amount(), L.nestedKeys());
        var meta = icon.getItemMeta();
        List<String> lore = new ArrayList<>();
        if (meta != null && meta.hasLore() && meta.getLore() != null) lore.addAll(meta.getLore());
        lore.add(ColorUtil.colorize("&8——"));
        lore.add(ColorUtil.colorize("&7卖家 &f" + L.sellerName()));
        lore.add(ColorUtil.colorize("&7来自 &f" + plugin.prettyName(L.serverCode())));
        String price = plugin.vault().ok() ? plugin.vault().format(L.price()) : String.valueOf(L.price());
        lore.add(ColorUtil.colorize("&7价格 &a" + price));
        if (hint != null && !hint.isBlank()) lore.add(ColorUtil.colorize(hint));
        if (meta != null) {
            meta.setLore(lore);
            icon.setItemMeta(meta);
        }
        return tag("listing", L.seller().toString(), L.id(), icon);
    }

    private ItemStack tag(String act, String data, long id, ItemStack stack) {
        return Items.tag(plugin, stack, act, data, id);
    }

    private ItemStack pane() {
        return Items.glass(Material.GRAY_STAINED_GLASS_PANE);
    }

    private void fillGlass(Inventory inv) {
        ItemStack g = pane();
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, g);
    }

    private void async(Runnable r) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                r.run();
            } catch (Exception e) {
                plugin.getLogger().warning(e.getMessage());
            }
        });
    }

    private void sync(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }
}
