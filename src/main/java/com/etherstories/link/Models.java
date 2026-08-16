package com.etherstories.link;

import java.util.UUID;

public final class Models {

    public record ServerRow(String code, String name, String blurb, String color, String icon, long heartbeat, long clock) {
        public boolean online(long offlineAfterMs) {
            if (heartbeat <= 0) return false;
            long age = clock - heartbeat;
            return age >= 0 && age < offlineAfterMs;
        }
    }

    public record Listing(long id, UUID seller, String sellerName, String serverCode,
                          String itemKey, String itemName, int amount, double price,
                          long created, byte[] blob, String nestedKeys) {}

    public record QueueRow(long id, String fromCode, String toCode, String pairCode,
                           String itemKey, String itemName, int amount, String status,
                           byte[] blob, String nestedKeys) {}

    public record WatchRow(String kind, int nodeId, String unit, String role,
                           String serverCode, String pairCode, String status, String ownerName) {}

    public record ChestRow(int id, String serial, String pairCode, String serverCode, String role,
                           String world, int x, int y, int z, UUID owner, String status, String ownerName,
                           String peerSerial, String itemFilter, int bounceId, String signFace) {
        public String unit() { return Units.or(serial, id); }
        public String peerUnit() { return peerSerial == null ? "" : peerSerial.trim(); }
        public String itemFilter() { return itemFilter == null ? "" : itemFilter.trim(); }
        public String signFace() { return signFace == null ? "" : signFace.trim(); }
        public ChestRow withStatus(String st) {
            return new ChestRow(id, serial, pairCode, serverCode, role, world, x, y, z, owner, st, ownerName, peerSerial, itemFilter, bounceId, signFace);
        }
    }

    public record IoRow(int id, String serial, String pairCode, String serverCode, String role,
                        String world, int x, int y, int z, UUID owner, String status, String ownerName,
                        int level, long updatedMs, String peerSerial, int peerLevel, long peerUpdatedMs,
                        String peerServer, long dbNow, String logic) {
        public String unit() { return Units.or(serial, id); }
        public String peerUnit() { return peerSerial == null ? "" : peerSerial.trim(); }
        public String logic() { return logic == null || logic.isBlank() ? "normal" : logic; }
        public IoRow withStatus(String st) {
            return new IoRow(id, serial, pairCode, serverCode, role, world, x, y, z, owner, st, ownerName,
                    level, updatedMs, peerSerial, peerLevel, peerUpdatedMs, peerServer, dbNow, logic);
        }
    }

    public record AlertRow(long id, String kind, String fromCode, String fromName,
                           String playerName, String detail, long created) {}

    public record ChatRow(long id, String fromCode, String fromName, UUID playerUuid, String playerName, String message,
                          String itemKey, String itemName, int itemAmount, byte[] itemBlob) {}
}
