package com.etherstories.link;

import org.bukkit.Bukkit;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public final class GuiListener implements Listener {
    private final ESLinkPlugin plugin;

    public GuiListener(ESLinkPlugin plugin) { this.plugin = plugin; }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.sessions().clear(e.getPlayer());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof LinkHolder) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof LinkHolder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        ItemStack stack = e.getCurrentItem();
        String act = Items.act(plugin, stack);
        if (act.isEmpty() || act.equals("noop") || act.equals("search-info")) {
            if (tryBindBounceSlot(p, holder, e.getRawSlot())) return;
            return;
        }
        Sessions.State st = plugin.sessions().of(p);

        switch (act) {
            case "home" -> plugin.gui().openHome(p);
            case "market" -> {
                if (holder.kind.equals("home")) {
                    st.serverFilter = null;
                    st.query = "";
                    st.marketPage = 0;
                }
                plugin.gui().openMarket(p);
            }
            case "mine" -> plugin.gui().openMine(p);
            case "sell" -> plugin.gui().beginSell(p);
            case "help" -> plugin.gui().openChestMenu(p);
            case "io" -> plugin.gui().openIoMenu(p);
            case "chest-tx", "node-tx" -> {
                if ("io".equals(st.pairKind)) plugin.io().setup(p, "TX");
                else plugin.chests().setup(p, "TX");
            }
            case "chest-rx", "node-rx" -> {
                if ("io".equals(st.pairKind)) plugin.io().setup(p, "RX");
                else plugin.chests().setup(p, "RX");
            }
            case "chest-unlink", "io-unlink", "node-unlink" -> confirmUnlink(p);
            case "mynodes" -> plugin.gui().openMyNodes(p);
            case "my-node" -> {
                String kind = Items.data(plugin, stack);
                int nid = (int) Items.id(plugin, stack);
                if (e.isRightClick()) toggleWatch(p, kind, nid);
                else plugin.gui().pingNode(p, kind, nid);
            }
            case "node-pause" -> {
                String kind = Items.data(plugin, stack);
                int nid = (int) Items.id(plugin, stack);
                if ("io".equals(kind)) plugin.io().togglePause(p, nid);
                else plugin.chests().togglePause(p, nid);
            }
            case "node-bounce" -> {
                int nid = (int) Items.id(plugin, stack);
                if (nid <= 0) nid = plugin.sessions().of(p).pendingChestId == null ? 0
                        : plugin.sessions().of(p).pendingChestId;
                plugin.chests().beginBindBounce(p, nid);
            }
            case "node-sign" -> {
                org.bukkit.block.Block node = ChestListener.sessionNode(plugin, p);
                if (node == null) {
                    plugin.msg(p, "&c请看准节点");
                    return;
                }
                if (!manageLooked(p, node)) return;
                if ("io".equals(st.pairKind)) {
                    try {
                        var row = plugin.store().ioAt(plugin.serverCode(),
                                node.getWorld().getName(), node.getX(), node.getY(), node.getZ());
                        if (row != null) plugin.io().refreshSign(row);
                    } catch (Exception ignored) {
                    }
                } else {
                    try {
                        var row = plugin.store().chestAt(plugin.serverCode(),
                                node.getWorld().getName(), node.getX(), node.getY(), node.getZ());
                        if (row == null && plugin.chests() != null)
                            row = plugin.chests().cachedAt(node.getWorld().getName(), node.getX(), node.getY(), node.getZ());
                        BlockFace stored = row == null ? null : ChestListener.parseFace(row.signFace());
                        BlockFace face = stored != null ? stored : ChestListener.faceFromPlayer(p, node);
                        ChestListener.ensureSign(node, face);
                        if (row != null && face != null) {
                            Models.ChestRow r = row;
                            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                                try { plugin.store().setSignFace(r.id(), face.name()); } catch (Exception ignored) {}
                            });
                            plugin.chests().refreshSign(row);
                        }
                    } catch (Exception ignored) {
                    }
                }
                plugin.msg(p, "&a已补牌子");
                plugin.gui().openNodeMenu(p, node);
            }
            case "node-clear" -> {
                org.bukkit.block.Block node = ChestListener.sessionNode(plugin, p);
                if (node == null) {
                    plugin.msg(p, "&c请看准箱子");
                    return;
                }
                if (!manageLooked(p, node)) return;
                int n = plugin.chests().clearBarriers(node);
                int cid = st.pendingChestId == null ? 0 : st.pendingChestId;
                if (n > 0 && cid > 0) {
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            plugin.store().setChestStatus(cid, "linked");
                            var row = plugin.store().chestById(cid);
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (row != null) plugin.chests().refreshSign(row);
                                plugin.msg(p, "&a已清掉 " + n + " 个屏障");
                                plugin.gui().openNodeMenu(p, node);
                            });
                        } catch (Exception ex) {
                            Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&a已清掉 " + n + " 个屏障"));
                        }
                    });
                    return;
                }
                plugin.msg(p, "&7没有屏障");
                plugin.gui().openNodeMenu(p, node);
            }
            case "io-tx" -> plugin.io().setup(p, "TX");
            case "io-rx" -> plugin.io().setup(p, "RX");
            case "node-pair" -> {
                if (st.pendingChestId == null || st.pendingChestId == 0) {
                    plugin.msg(p, "&c先登记发送或接收");
                    return;
                }
                org.bukkit.block.Block looked = ChestListener.sessionNode(plugin, p);
                if (looked != null && !manageLooked(p, looked)) return;
                plugin.gui().openPairServers(p);
            }
            case "node-logic" -> cycleIoLogic(p, (int) Items.id(plugin, stack));
            case "guide" -> GuideBook.open(plugin, p);
            case "chat" -> plugin.gui().openChat(p);
            case "chat-send" -> {
                plugin.chat().setAll(p, "all".equalsIgnoreCase(Items.data(plugin, stack)));
                plugin.gui().openChat(p);
            }
            case "chat-recv" -> {
                if ("all".equalsIgnoreCase(Items.data(plugin, stack))) plugin.chat().setRecvAll(p);
                else plugin.chat().setRecvLocal(p);
                plugin.gui().openChat(p);
            }
            case "chat-srv" -> {
                plugin.chat().toggleRecvServer(p, Items.data(plugin, stack));
                plugin.gui().openChat(p);
            }
            case "pick-market" -> {
                String id = Items.data(plugin, stack);
                if (!plugin.markets().select(p, id)) {
                    plugin.msg(p, "无法切换到该市场。");
                    return;
                }
                plugin.msg(p, "已切换至「" + plugin.markets().selectedName(p) + "」。");
                st.marketPage = 0;
                st.serverFilter = null;
                plugin.gui().openMarket(p);
            }
            case "filter-cycle" -> plugin.gui().cycleMarketFilter(p);
            case "list-alert" -> {
                plugin.toggleListingAlert(p);
                plugin.gui().openHome(p);
            }
            case "node-filter" -> {
                int nid = (int) Items.id(plugin, stack);
                ItemStack hand = p.getInventory().getItemInMainHand();
                String filter = "";
                if (hand != null && !hand.getType().isAir()) {
                    String key = Items.itemKey(hand);
                    if (p.isSneaking()) {
                        int i = key.indexOf(':');
                        filter = i < 0 ? key : key.substring(0, i);
                    } else {
                        filter = key;
                    }
                }
                plugin.chests().setFilter(p, nid, filter);
            }
            case "admin-nodes" -> plugin.gui().openAdminNodes(p);
            case "admin-node" -> {
                String kind = Items.data(plugin, stack);
                int nid = (int) Items.id(plugin, stack);
                if (e.isRightClick()) deleteAdminNode(p, kind, nid);
                else plugin.gui().pingNode(p, kind, nid);
            }
            case "watches" -> plugin.gui().openWatches(p);
            case "node-watch" -> toggleWatch(p, Items.data(plugin, stack), (int) Items.id(plugin, stack));
            case "watch-off" -> {
                toggleWatchOff(p, Items.data(plugin, stack), (int) Items.id(plugin, stack));
            }
            case "admin" -> plugin.gui().openSettings(p);
            case "settings" -> plugin.gui().openSettings(p);
            case "tog" -> {
                if (!p.hasPermission("eslink.admin")) return;
                plugin.toggleCfg(Items.data(plugin, stack));
                plugin.gui().openSettings(p);
            }
            case "trade-tog" -> {
                if (!p.hasPermission("eslink.admin")) return;
                plugin.setTradeEnabled(!plugin.tradeEnabled());
                plugin.gui().openSettings(p);
            }
            case "tax-down" -> {
                if (!p.hasPermission("eslink.admin")) return;
                plugin.setTaxRate(plugin.taxRate() - 0.01);
                plugin.gui().openSettings(p);
            }
            case "tax-up" -> {
                if (!p.hasPermission("eslink.admin")) return;
                plugin.setTaxRate(plugin.taxRate() + 0.01);
                plugin.gui().openSettings(p);
            }
            case "colors" -> plugin.gui().openColors(p);
            case "set-color" -> {
                if (!p.hasPermission("eslink.admin")) return;
                plugin.setServerColor(Items.data(plugin, stack));
                plugin.gui().openSettings(p);
            }
            case "shape" -> {
                if (!p.hasPermission("eslink.admin")) return;
                plugin.setServerIcon(plugin.serverIcon().contains("CONCRETE") ? "TERRACOTTA" : "CONCRETE");
                plugin.gui().openSettings(p);
            }
            case "servers" -> plugin.gui().openServers(p);
            case "srv-del" -> deleteGhost(p, Items.data(plugin, stack));
            case "search" -> {
                st.awaitingSearch = true;
                st.awaitingPrice = false;
                p.closeInventory();
                plugin.msg(p, "请在聊天栏输入要搜索的物品名，回车后返回市场。输入 cancel 取消。");
            }
            case "search-clear" -> {
                st.query = "";
                st.marketPage = 0;
                plugin.gui().openMarket(p);
            }
            case "filter" -> {
                String code = Items.data(plugin, stack);
                st.serverFilter = code.isBlank() ? null : code;
                st.marketPage = 0;
                plugin.gui().openMarket(p);
            }
            case "page" -> {
                if ("prev".equals(Items.data(plugin, stack))) st.marketPage = Math.max(0, st.marketPage - 1);
                else st.marketPage++;
                plugin.gui().openMarket(p);
            }
            case "listing" -> {
                long id = Items.id(plugin, stack);
                if (holder.kind.equals("mine")) {
                    if (e.isRightClick()) plugin.gui().beginReprice(p, id);
                    else plugin.gui().unlist(p, id);
                    return;
                }
                if (e.isRightClick()) {
                    try {
                        UUID seller = UUID.fromString(Items.data(plugin, stack));
                        plugin.gui().openSeller(p, seller);
                    } catch (Exception ignored) {
                    }
                    return;
                }
                plugin.gui().openConfirm(p, id);
            }
            case "buy-no" -> plugin.gui().openMarket(p);
            case "buy-yes" -> plugin.gui().doBuy(p, Items.id(plugin, stack));
            case "ban" -> adminBan(p, Items.data(plugin, stack), true);
            case "unban" -> adminBan(p, Items.data(plugin, stack), false);
            case "wipe" -> adminWipe(p, Items.data(plugin, stack));
            case "pair-srv" -> plugin.gui().openPairChests(p, Items.data(plugin, stack));
            case "pair-back" -> plugin.gui().openPairServers(p);
            case "pair-cancel" -> {
                st.awaitingPair = false;
                p.closeInventory();
                plugin.msg(p, "已中止配对。节点仍保留，可稍后再次连接。");
            }
            case "pair-pick" -> plugin.gui().pairRemote(p, (int) Items.id(plugin, stack));
            default -> {
            }
        }
    }

    private boolean tryBindBounceSlot(Player p, LinkHolder holder, int slot) {
        if (!"node".equals(holder.kind)) return false;
        if (slot != 23 && slot != 4) return false;
        Sessions.State st = plugin.sessions().of(p);
        if (!"chest".equals(st.pairKind) || !"TX".equals(st.pairRole)) return false;
        int nid = st.pendingChestId == null ? 0 : st.pendingChestId;
        if (nid <= 0) return false;
        plugin.chests().beginBindBounce(p, nid);
        return true;
    }

    private boolean manageLooked(Player p, org.bukkit.block.Block node) {
        if (node == null) return true;
        if (plugin.chests() != null) {
            Models.ChestRow c = plugin.chests().cachedAt(
                    node.getWorld().getName(), node.getX(), node.getY(), node.getZ());
            if (c != null) {
                if (plugin.canManage(p, c.owner())) return true;
                plugin.msg(p, "&c这是别人的节点");
                return false;
            }
        }
        if (plugin.io() != null) {
            Models.IoRow n = plugin.io().cachedAt(
                    node.getWorld().getName(), node.getX(), node.getY(), node.getZ());
            if (n != null && !plugin.canManage(p, n.owner())) {
                plugin.msg(p, "&c这是别人的节点");
                return false;
            }
        }
        return true;
    }

    private void deleteAdminNode(Player p, String kind, int id) {
        if (!p.hasPermission("eslink.admin") || id <= 0) return;
        String key = ("io".equals(kind) ? "io" : "chest") + ":" + id;
        Sessions.State st = plugin.sessions().of(p);
        if (!key.equalsIgnoreCase(st.pendingDelete)) {
            st.pendingDelete = key;
            plugin.msg(p, "&e再点一次确认删除登记");
            plugin.gui().openAdminNodes(p);
            return;
        }
        st.pendingDelete = null;
        boolean io = "io".equals(kind);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (io) {
                    Models.IoRow row = plugin.store().ioById(id);
                    plugin.store().deleteIo(id);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (row != null) {
                            org.bukkit.World w = Bukkit.getWorld(row.world());
                            if (w != null) plugin.io().removeAt(w.getBlockAt(row.x(), row.y(), row.z()), row);
                        }
                        plugin.msg(p, "&c已删除红石登记 UNIT " + (row == null ? id : row.unit()));
                        plugin.gui().openAdminNodes(p);
                    });
                } else {
                    Models.ChestRow row = plugin.store().chestById(id);
                    plugin.store().deleteChest(id);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (row != null) {
                            org.bukkit.World w = Bukkit.getWorld(row.world());
                            org.bukkit.block.Block b = w == null ? null : w.getBlockAt(row.x(), row.y(), row.z());
                            plugin.markRxNode(b, false);
                            if (b != null && b.getState() instanceof org.bukkit.block.Chest ch) {
                                ch.setCustomName(null);
                                ch.update();
                            }
                            if (b != null) {
                                var sign = ChestListener.findSign(b);
                                if (sign != null) sign.getBlock().setType(org.bukkit.Material.AIR);
                            }
                        }
                        plugin.msg(p, "&c已删除互通箱登记 UNIT " + (row == null ? id : row.unit()));
                        plugin.gui().openAdminNodes(p);
                    });
                }
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c删除失败: " + e.getMessage()));
            }
        });
    }

    private void deleteGhost(Player p, String code) {
        if (!plugin.isSuper(p) || code == null || code.isBlank()) return;
        if (code.equalsIgnoreCase(plugin.serverCode())) {
            plugin.msg(p, "&c不能删除本服");
            return;
        }
        Sessions.State st = plugin.sessions().of(p);
        if (!code.equalsIgnoreCase(st.pendingDelete)) {
            st.pendingDelete = code;
            plugin.msg(p, "&e再点一次确认删除 &f" + plugin.prettyName(code));
            plugin.gui().openServers(p);
            return;
        }
        st.pendingDelete = null;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.store().deleteServer(code);
                plugin.rememberServers(plugin.store().servers());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.msg(p, "&c已删除服务器记录 " + plugin.prettyName(code));
                    plugin.gui().openServers(p);
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c删除失败: " + e.getMessage()));
            }
        });
    }

    private void adminBan(Player p, String uuidStr, boolean ban) {
        if (!p.hasPermission("eslink.admin")) return;
        UUID u;
        try {
            u = UUID.fromString(uuidStr);
        } catch (Exception e) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (ban) plugin.store().setBan(plugin.serverCode(), u, "admin");
                else plugin.store().unban(plugin.serverCode(), u);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.msg(p, ban ? "&c已禁止该成员在本服上架/放箱" : "&a已解禁");
                    plugin.gui().openSeller(p, u);
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c操作失败"));
            }
        });
    }

    private void adminWipe(Player p, String uuidStr) {
        if (!p.hasPermission("eslink.admin")) return;
        UUID u;
        try {
            u = UUID.fromString(uuidStr);
        } catch (Exception e) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                int n = plugin.markets().deleteOf(p, plugin.serverCode(), u);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.msg(p, "已下架 " + n + " 件（当前市场、本服来源）。");
                    plugin.gui().openSeller(p, u);
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c失败"));
            }
        });
    }

    private void confirmUnlink(Player p) {
        Sessions.State st = plugin.sessions().of(p);
        String key = ("io".equals(st.pairKind) ? "io" : "chest") + ":"
                + (st.pendingChestId == null ? 0 : st.pendingChestId);
        if (!key.equals(st.pendingUnlink)) {
            st.pendingUnlink = key;
            plugin.msg(p, "&e再点一次确认拆除");
            org.bukkit.block.Block node = ChestListener.sessionNode(plugin, p);
            if (node != null) plugin.gui().openNodeMenu(p, node);
            return;
        }
        st.pendingUnlink = null;
        if ("io".equals(st.pairKind)) plugin.io().unlink(p);
        else plugin.chests().unlink(p);
    }

    private void toggleWatch(Player p, String kind, int nodeId) {
        if (nodeId <= 0 || kind == null || kind.isBlank()) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                boolean on = !plugin.store().watching(p.getUniqueId(), kind, nodeId);
                plugin.store().setWatch(p.getUniqueId(), p.getName(), kind, nodeId, on);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.msg(p, on ? "&a已接收此节点的信息" : "&7已关闭此节点的信息");
                    if (plugin.sessions().of(p).page == Sessions.Page.NODES) {
                        plugin.gui().openMyNodes(p);
                        return;
                    }
                    org.bukkit.block.Block node = ChestListener.sessionNode(plugin, p);
                    if (node != null) plugin.gui().openNodeMenu(p, node);
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c开关失败"));
            }
        });
    }

    private void toggleWatchOff(Player p, String kind, int nodeId) {
        if (nodeId <= 0 || kind == null || kind.isBlank()) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.store().setWatch(p.getUniqueId(), p.getName(), kind, nodeId, false);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.msg(p, "&7已关闭此节点的信息");
                    plugin.gui().openWatches(p);
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c关闭失败"));
            }
        });
    }

    private void cycleIoLogic(Player p, int id) {
        if (id <= 0) {
            plugin.msg(p, "&c先登记为接收端，再改逻辑");
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Models.IoRow row = plugin.store().ioById(id);
                if (row == null) {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c节点不存在"));
                    return;
                }
                if (!plugin.canManage(p, row.owner())) {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c这是别人的节点"));
                    return;
                }
                if (!"RX".equals(row.role())) {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c只有接收端能改逻辑"));
                    return;
                }
                String next = NodeSigns.logicNext(row.logic());
                plugin.store().setIoLogic(id, next);
                Models.IoRow fresh = plugin.store().ioById(id);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.io().refreshSign(fresh);
                    org.bukkit.block.Block node = ChestListener.sessionNode(plugin, p);
                    if (node != null) plugin.gui().openNodeMenu(p, node);
                    plugin.msg(p, "&a接收逻辑: " + NodeSigns.logicCn(next) + "  ·  离线仍为 0");
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "&c修改失败"));
            }
        });
    }

}
