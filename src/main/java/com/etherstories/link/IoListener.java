package com.etherstories.link;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

public final class IoListener implements Listener {
    private static final BlockFace[] NEAR = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST,
            BlockFace.UP, BlockFace.DOWN, BlockFace.SELF
    };
    private final ESLinkPlugin plugin;

    public IoListener(ESLinkPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRedstone(BlockRedstoneEvent e) {
        IoNet io = plugin.ioEnabled() ? plugin.io() : null;
        if (io == null) return;
        // 接收端现在是标靶，本身就是红石电源，原版会自己把电传给邻居，不需要插件插手。
        if (!io.hasTxNodes()) return;
        Block src = e.getBlock();
        for (BlockFace f : NEAR) {
            io.onPowerHint(f == BlockFace.SELF ? src : src.getRelative(f));
        }
    }

    @EventHandler
    public void onUnload(ChunkUnloadEvent e) {
        if (plugin.io() != null) plugin.io().onChunkUnload(e.getChunk());
    }
}
