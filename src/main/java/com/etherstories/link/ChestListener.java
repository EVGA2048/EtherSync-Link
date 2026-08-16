package com.etherstories.link;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class ChestListener implements Listener {
    private static final BlockFace[] HORIZ = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };
    private static final BlockFace[] AROUND = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP
    };
    private final ESLinkPlugin plugin;

    public ChestListener(ESLinkPlugin plugin) { this.plugin = plugin; }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Block loc = null;
        if (b.getState() instanceof Chest) loc = b;
        else if (b.getState() instanceof Sign sign) {
            Block chest = attachedChest(b);
            if (chest != null && plugin.chests() != null) {
                Models.ChestRow row = plugin.chests().cachedAt(
                        chest.getWorld().getName(), chest.getX(), chest.getY(), chest.getZ());
                BlockFace face = faceOf(sign);
                if (row != null && face != null) {
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try { plugin.store().setSignFace(row.id(), face.name()); } catch (Exception ignored) {}
                    });
                }
            }
            return;
        } else if (b.getType() != Material.LEVER) {
            loc = b;
        }
        if (loc == null) return;
        if (!plugin.store().ready()) return;
        Player p = e.getPlayer();
        Models.ChestRow cachedChest = plugin.chests() == null ? null
                : plugin.chests().cachedAt(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ());
        Models.IoRow cachedIo = plugin.io() == null ? null
                : plugin.io().cachedAt(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ());
        if (cachedChest != null && !plugin.canManage(p, cachedChest.owner())) {
            e.setCancelled(true);
            plugin.msg(p, "&c这是别人的互通箱");
            return;
        }
        if (cachedIo != null && !plugin.canManage(p, cachedIo.owner())) {
            e.setCancelled(true);
            plugin.msg(p, "&c这是别人的红石节点");
            return;
        }
        if (cachedChest == null && cachedIo == null) return;
        if (!p.isSneaking()) {
            e.setCancelled(true);
            plugin.msg(p, "&e这是互通节点。蹲下再挖才拆除，或蹲下左键牌子打开菜单。");
            return;
        }
        final Block target = loc;
        if (cachedChest != null) {
            Models.ChestRow row = cachedChest;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    plugin.store().deleteChest(row.id());
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        plugin.markRxNode(target, false);
                        if (target.getState() instanceof Chest ch) {
                            ch.setCustomName(null);
                            ch.update();
                        }
                        plugin.msg(p, "&7已拆除运输箱 UNIT " + row.unit());
                    });
                } catch (Exception ignored) {
                }
            });
        }
        if (cachedIo != null) {
            Models.IoRow io = cachedIo;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    plugin.store().deleteIo(io.id());
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        plugin.io().removeAt(target, io);
                        plugin.msg(p, "&7已拆除红石节点 UNIT " + io.unit());
                    });
                } catch (Exception ignored) {
                }
            });
        }
    }

    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!plugin.store().ready()) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    var chests = plugin.store().chestsOf(p.getUniqueId());
                    var ios = plugin.store().ioOf(p.getUniqueId());
                    java.util.List<String> bits = new java.util.ArrayList<>();
                    for (var c : chests) {
                        if (NodeSigns.trouble(c.status())) bits.add("UNIT " + c.unit() + " " + NodeSigns.label(c.status()));
                    }
                    for (var n : ios) {
                        boolean paired = n.pairCode() != null && !n.pairCode().isBlank();
                        boolean down = paired && plugin.io() != null && !plugin.io().isLive(n);
                        if (NodeSigns.trouble(n.status()) || down) {
                            bits.add("UNIT " + n.unit() + " " + (down && !NodeSigns.trouble(n.status())
                                    ? "离线" : NodeSigns.label(n.status())));
                        }
                    }
                    if (bits.isEmpty()) return;
                    String line = bits.size() <= 3 ? String.join(" · ", bits)
                            : bits.get(0) + " · " + bits.get(1) + " 等 " + bits.size() + " 个";
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (p.isOnline()) plugin.msg(p, "&e你有节点异常: &f" + line + "  &7/link → 我的节点");
                    });
                } catch (Exception ignored) {
                }
            });
        }, 40L);
    }

    @EventHandler
    public void onSign(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.LEFT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block hit = e.getClickedBlock();
        if (hit == null) return;
        Integer bind = plugin.sessions().of(e.getPlayer()).bindBounceFor;
        if (bind != null && e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            Block chest = hit.getState() instanceof Chest ? hit : attachedChest(hit);
            if (chest != null && chest.getState() instanceof Chest) {
                e.setCancelled(true);
                plugin.chests().bindBounce(e.getPlayer(), bind, chest);
                return;
            }
        }
        if (!e.getPlayer().isSneaking()) return;
        Block node = null;
        if (hit.getState() instanceof Sign) {
            node = attachedChest(hit);
            if (node == null) node = attachedSolid(hit);
        } else if (e.getAction() == Action.RIGHT_CLICK_BLOCK
                && (e.getItem() == null || e.getItem().getType().isAir())) {
            if (hit.getState() instanceof Chest) node = hit;
            else if (IoNet.isIoBody(hit.getType())) node = hit;
        }
        if (node == null) return;
        e.setCancelled(true);
        plugin.gui().openNodeMenu(e.getPlayer(), node);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent e) {
        if (Items.hopperLocked(plugin, e.getItem())) {
            e.setCancelled(true);
            return;
        }
        Block b = holderBlock(e.getDestination().getHolder());
        if (b != null && plugin.isRx(b.getWorld().getName(), b.getX(), b.getY(), b.getZ())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onRxClick(InventoryClickEvent e) {
        Block b = holderBlock(e.getInventory().getHolder());
        if (b == null) return;
        if (!plugin.isRx(b.getWorld().getName(), b.getX(), b.getY(), b.getZ())) return;
        if (e.getClickedInventory() == e.getView().getTopInventory()
                && e.getClick().isShiftClick()) {
            e.setCancelled(true);
            return;
        }
        if (e.getClickedInventory() == e.getView().getBottomInventory() && e.isShiftClick()) {
            e.setCancelled(true);
            if (e.getWhoClicked() instanceof Player p) plugin.msg(p, "&eRX 仅接收，禁止人工放入");
            return;
        }
        if (e.getClickedInventory() == e.getView().getTopInventory()
                && e.getCursor() != null && e.getCursor().getType() != Material.AIR) {
            e.setCancelled(true);
            if (e.getWhoClicked() instanceof Player p) plugin.msg(p, "&eRX 仅接收，禁止人工放入");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlaceMark(org.bukkit.event.block.BlockPlaceEvent e) {
        if (Items.hopperLocked(plugin, e.getItemInHand())) {
            e.setCancelled(true);
            plugin.msg(e.getPlayer(), "&c这是运输标记，不能放到地上");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTakeMark(InventoryClickEvent e) {
        if (e.getInventory().getHolder() instanceof LinkHolder) return;
        if (Items.hopperLocked(plugin, e.getCurrentItem()) || Items.hopperLocked(plugin, e.getCursor())) {
            e.setCancelled(true);
            if (e.getWhoClicked() instanceof Player p) plugin.msg(p, "&c这是运输标记，菜单里「清屏障」才能去掉");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDropMark(org.bukkit.event.player.PlayerDropItemEvent e) {
        if (Items.hopperLocked(plugin, e.getItemDrop().getItemStack())) {
            e.setCancelled(true);
            plugin.msg(e.getPlayer(), "&c这是运输标记，不能扔掉");
        }
    }

    /** Arclight 没有 Player.getTargetBlockFace(int)，用玩家相对箱子的方向。 */
    static BlockFace faceFromPlayer(Player p, Block chest) {
        var eye = p.getEyeLocation().toVector();
        var mid = chest.getLocation().add(0.5, 0.5, 0.5).toVector();
        var d = eye.subtract(mid);
        if (Math.abs(d.getX()) > Math.abs(d.getZ())) {
            return d.getX() > 0 ? BlockFace.EAST : BlockFace.WEST;
        }
        return d.getZ() > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    static Block sessionNode(ESLinkPlugin plugin, Player p) {
        Sessions.State st = plugin.sessions().of(p);
        if (st.hasLook && st.lookWorld != null) {
            org.bukkit.World w = org.bukkit.Bukkit.getWorld(st.lookWorld);
            if (w != null) {
                Block b = w.getBlockAt(st.lookX, st.lookY, st.lookZ);
                if (!b.getType().isAir()) return b;
            }
        }
        return lookingNode(p);
    }

    static Block lookingNode(Player p) {
        Block hit = p.getTargetBlockExact(6, FluidCollisionMode.NEVER);
        if (hit == null) return null;
        if (hit.getState() instanceof Sign) return attachedSolid(hit);
        if (hit.getType().isAir()) return null;
        return hit;
    }

    static Block attachedSolid(Block sign) {
        if (sign.getBlockData() instanceof WallSign ws) {
            Block behind = sign.getRelative(ws.getFacing().getOppositeFace());
            if (!behind.getType().isAir()) return behind;
        }
        if (sign.getBlockData() instanceof Directional d && !(sign.getBlockData() instanceof WallSign)) {
            Block behind = sign.getRelative(d.getFacing().getOppositeFace());
            if (!behind.getType().isAir()) return behind;
        }
        for (BlockFace f : AROUND) {
            Block n = sign.getRelative(f);
            if (!n.getType().isAir() && !(n.getState() instanceof Sign)) return n;
        }
        return null;
    }

    static Block sessionChest(ESLinkPlugin plugin, Player p) {
        Sessions.State st = plugin.sessions().of(p);
        if (st.hasLook && st.lookWorld != null) {
            org.bukkit.World w = org.bukkit.Bukkit.getWorld(st.lookWorld);
            if (w != null) {
                Block b = w.getBlockAt(st.lookX, st.lookY, st.lookZ);
                if (b.getState() instanceof Chest) return b;
            }
        }
        return lookingChest(p);
    }

    static void rememberLook(ESLinkPlugin plugin, Player p, Block chest) {
        Sessions.State st = plugin.sessions().of(p);
        st.hasLook = true;
        st.lookWorld = chest.getWorld().getName();
        st.lookX = chest.getX();
        st.lookY = chest.getY();
        st.lookZ = chest.getZ();
    }

    static void clearLook(ESLinkPlugin plugin, Player p) {
        plugin.sessions().of(p).hasLook = false;
    }

    static Block lookingChest(Player p) {
        Block hit = p.getTargetBlockExact(6, FluidCollisionMode.NEVER);
        if (hit == null) return null;
        if (hit.getState() instanceof Chest) return hit;
        if (hit.getState() instanceof Sign) return attachedChest(hit);
        return null;
    }

    static Inventory chestInv(Block b) {
        if (b != null && b.getState() instanceof Chest c) return c.getInventory();
        return null;
    }

    static Inventory chestInv(Chest chest) {
        return chest == null ? null : chest.getInventory();
    }

    /** 只读方块数据，不要 getInventory（异步会卡死 Arclight）。 */
    static Block otherHalf(Block b) {
        if (b == null) return null;
        try {
            if (!(b.getBlockData() instanceof org.bukkit.block.data.type.Chest data)) return null;
            var type = data.getType();
            if (type == org.bukkit.block.data.type.Chest.Type.SINGLE) return null;
            BlockFace dir = type == org.bukkit.block.data.type.Chest.Type.LEFT
                    ? rightOf(data.getFacing()) : leftOf(data.getFacing());
            if (dir == null) return null;
            Block o = b.getRelative(dir);
            return o.getType() == b.getType() ? o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static BlockFace leftOf(BlockFace f) {
        return switch (f) {
            case NORTH -> BlockFace.EAST;
            case SOUTH -> BlockFace.WEST;
            case EAST -> BlockFace.SOUTH;
            case WEST -> BlockFace.NORTH;
            default -> null;
        };
    }

    private static BlockFace rightOf(BlockFace f) {
        return switch (f) {
            case NORTH -> BlockFace.WEST;
            case SOUTH -> BlockFace.EAST;
            case EAST -> BlockFace.NORTH;
            case WEST -> BlockFace.SOUTH;
            default -> null;
        };
    }

    static java.util.List<Block> halves(Block b) {
        java.util.List<Block> out = new java.util.ArrayList<>();
        if (b == null) return out;
        out.add(b);
        Block o = otherHalf(b);
        if (o != null) out.add(o);
        return out;
    }

    static BlockFace blockFacing(Block b) {
        if (b != null && b.getBlockData() instanceof Directional d) return d.getFacing();
        return null;
    }

    static BlockFace faceOf(Sign sign) {
        if (sign == null) return null;
        if (sign.getBlockData() instanceof WallSign ws) return ws.getFacing();
        if (sign.getBlockData() instanceof Directional d) return d.getFacing();
        return null;
    }

    static BlockFace parseFace(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return BlockFace.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return null;
        }
    }

    static Sign ensureSign(Block chest, BlockFace prefer) {
        Sign existing = findSign(chest);
        if (existing != null) return existing;
        BlockFace face = usableHoriz(prefer);
        if (face == null) face = usableHoriz(blockFacing(chest));
        Block dest = face == null ? null : chest.getRelative(face);
        if (dest == null || !dest.getType().isAir()) {
            for (BlockFace f : HORIZ) {
                if (face != null && f == face) continue;
                if (chest.getRelative(f).getType().isAir()) {
                    face = f;
                    dest = chest.getRelative(f);
                    break;
                }
            }
        }
        if (dest == null || !dest.getType().isAir() || face == null) return null;
        dest.setType(Material.OAK_WALL_SIGN);
        if (dest.getBlockData() instanceof WallSign ws) {
            ws.setFacing(face);
            dest.setBlockData(ws);
        }
        return dest.getState() instanceof Sign s ? s : null;
    }

    private static BlockFace usableHoriz(BlockFace f) {
        if (f == null) return null;
        return switch (f) {
            case NORTH, SOUTH, EAST, WEST -> f;
            default -> null;
        };
    }

    private static Block holderBlock(InventoryHolder h) {
        if (h instanceof Chest c) return c.getBlock();
        if (h instanceof DoubleChest d && d.getLeftSide() instanceof Chest c) return c.getBlock();
        if (h instanceof Container c) return c.getBlock();
        return null;
    }

    static Block attachedChest(Block sign) {
        if (sign.getBlockData() instanceof WallSign ws) {
            Block behind = sign.getRelative(ws.getFacing().getOppositeFace());
            if (behind.getType() == Material.CHEST || behind.getType() == Material.TRAPPED_CHEST) return behind;
        }
        if (sign.getBlockData() instanceof Directional d && !(sign.getBlockData() instanceof WallSign)) {
            Block behind = sign.getRelative(d.getFacing().getOppositeFace());
            if (behind.getType() == Material.CHEST || behind.getType() == Material.TRAPPED_CHEST) return behind;
        }
        for (BlockFace f : AROUND) {
            Block n = sign.getRelative(f);
            if (n.getType() == Material.CHEST || n.getType() == Material.TRAPPED_CHEST) return n;
        }
        return null;
    }

    static Sign findSign(Block chest) {
        Sign s = findSignOn(chest);
        if (s != null) return s;
        Block o = otherHalf(chest);
        return o == null ? null : findSignOn(o);
    }

    private static Sign findSignOn(Block chest) {
        if (chest == null) return null;
        for (BlockFace f : AROUND) {
            Block n = chest.getRelative(f);
            if (n.getState() instanceof Sign sign) return sign;
        }
        return null;
    }
}
