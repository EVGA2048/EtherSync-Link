package com.etherstories.link;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Store {

    private final Plugin plugin;
    private HikariDataSource ds;

    public Store(Plugin plugin) { this.plugin = plugin; }

    public boolean connect() {
        FileConfiguration cfg = plugin.getConfig();
        String host = cfg.getString("mysql.host", "127.0.0.1");
        int port = cfg.getInt("mysql.port", 3306);
        String db = cfg.getString("mysql.database", "eslink");
        String user = cfg.getString("mysql.user", "root");
        String pass = cfg.getString("mysql.password", "");
        if (pass == null) pass = "";

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + db
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8");
        hc.setUsername(user);
        hc.setPassword(pass);
        hc.setMaximumPoolSize(4);
        hc.setMinimumIdle(0);
        hc.setPoolName("ESLink");
        hc.setConnectionTimeout(8000);
        try {
            ds = new HikariDataSource(hc);
            try (Connection c = ds.getConnection()) {
                initSchema(c);
            }
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("MySQL 连不上: " + e.getMessage());
            close();
            return false;
        }
    }

    public boolean ready() { return ds != null && !ds.isClosed(); }

    public void close() {
        if (ds != null) { ds.close(); ds = null; }
    }

    private void initSchema(Connection c) throws Exception {
        try (Statement s = c.createStatement()) {
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS link_servers (
                      code VARCHAR(16) PRIMARY KEY,
                      display_name VARCHAR(64) NOT NULL,
                      blurb VARCHAR(128) NOT NULL DEFAULT '',
                      last_heartbeat BIGINT NOT NULL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            try { s.executeUpdate("ALTER TABLE link_servers ADD COLUMN blurb VARCHAR(128) NOT NULL DEFAULT ''"); } catch (Exception ignored) {}
            try { s.executeUpdate("ALTER TABLE link_servers ADD COLUMN color VARCHAR(16) NOT NULL DEFAULT 'LIGHT_BLUE'"); } catch (Exception ignored) {}
            try { s.executeUpdate("ALTER TABLE link_servers ADD COLUMN icon VARCHAR(16) NOT NULL DEFAULT 'TERRACOTTA'"); } catch (Exception ignored) {}
            try { s.executeUpdate("ALTER TABLE link_servers ADD COLUMN short_name VARCHAR(16) NOT NULL DEFAULT ''"); } catch (Exception ignored) {}
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS link_listings (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      seller_uuid CHAR(36) NOT NULL,
                      seller_name VARCHAR(32) NOT NULL,
                      server_code VARCHAR(16) NOT NULL,
                      item_key VARCHAR(128) NOT NULL,
                      item_name VARCHAR(128) NOT NULL,
                      amount INT NOT NULL,
                      price DOUBLE NOT NULL,
                      created BIGINT NOT NULL,
                      blob_b64 MEDIUMTEXT,
                      INDEX idx_server (server_code),
                      INDEX idx_seller (seller_uuid)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS link_queue (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      from_code VARCHAR(16) NOT NULL,
                      to_code VARCHAR(16) NOT NULL,
                      pair_code VARCHAR(32) NOT NULL,
                      item_key VARCHAR(128) NOT NULL,
                      item_name VARCHAR(128) NOT NULL,
                      amount INT NOT NULL,
                      status VARCHAR(16) NOT NULL,
                      blob_b64 MEDIUMTEXT,
                      created BIGINT NOT NULL,
                      return_slots INT NOT NULL DEFAULT 1,
                      batch_id VARCHAR(36) NULL,
                      parent_id BIGINT NULL,
                      row_index INT NULL,
                      row_sha256 CHAR(64) NULL,
                      INDEX idx_status (status, to_code),
                      INDEX idx_batch (batch_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS link_batches (
                      batch_id VARCHAR(36) PRIMARY KEY,
                      from_code VARCHAR(16) NOT NULL,
                      to_code VARCHAR(16) NOT NULL,
                      item_count INT NOT NULL,
                      payload_sha256 CHAR(64) NOT NULL,
                      status VARCHAR(16) NOT NULL DEFAULT 'open',
                      created BIGINT NOT NULL,
                      INDEX idx_batches_status (status, created)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS link_item_escrow (
                      token CHAR(36) PRIMARY KEY,
                      origin_server VARCHAR(16) NOT NULL,
                      payload_b64 MEDIUMTEXT NOT NULL,
                      status VARCHAR(16) NOT NULL DEFAULT 'active',
                      claim_id CHAR(36) NULL,
                      claimed_at BIGINT NOT NULL DEFAULT 0,
                      created BIGINT NOT NULL,
                      INDEX idx_escrow_status (status, claimed_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS link_chests (
                      id INT AUTO_INCREMENT PRIMARY KEY,
                      pair_code VARCHAR(32) NULL,
                      server_code VARCHAR(16) NOT NULL,
                      role VARCHAR(4) NOT NULL,
                      world VARCHAR(64) NOT NULL,
                      x INT NOT NULL, y INT NOT NULL, z INT NOT NULL,
                      owner_uuid CHAR(36) NOT NULL,
                      status VARCHAR(16) NOT NULL DEFAULT 'idle',
                      UNIQUE KEY uniq_loc (server_code, world, x, y, z)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS link_bans (
                      server_code VARCHAR(16) NOT NULL,
                      player_uuid CHAR(36) NOT NULL,
                      reason VARCHAR(128),
                      PRIMARY KEY (server_code, player_uuid)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS link_chat (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      from_code VARCHAR(16) NOT NULL,
                      from_name VARCHAR(64) NOT NULL,
                      player_uuid CHAR(36) NOT NULL,
                      player_name VARCHAR(32) NOT NULL,
                      message VARCHAR(256) NOT NULL,
                      created BIGINT NOT NULL,
                      item_key VARCHAR(128) NULL,
                      item_name VARCHAR(128) NULL,
                      item_amount INT NULL,
                      item_b64 MEDIUMTEXT NULL,
                      INDEX idx_id (id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            try { s.executeUpdate("ALTER TABLE link_chat ADD COLUMN item_key VARCHAR(128) NULL"); } catch (Exception ignored) {}
            try { s.executeUpdate("ALTER TABLE link_chat ADD COLUMN item_name VARCHAR(128) NULL"); } catch (Exception ignored) {}
            try { s.executeUpdate("ALTER TABLE link_chat ADD COLUMN item_amount INT NULL"); } catch (Exception ignored) {}
            try { s.executeUpdate("ALTER TABLE link_chat ADD COLUMN item_b64 MEDIUMTEXT NULL"); } catch (Exception ignored) {}
            try { s.executeUpdate("ALTER TABLE link_chests ADD COLUMN owner_name VARCHAR(32) NULL"); } catch (Exception ignored) {}
            try { s.executeUpdate("ALTER TABLE link_chests ADD COLUMN serial VARCHAR(8) NULL"); } catch (Exception ignored) {}
            try { s.executeUpdate("ALTER TABLE link_chests MODIFY serial VARCHAR(16) NULL"); } catch (Exception ignored) {}
            try { s.executeUpdate("ALTER TABLE link_io MODIFY serial VARCHAR(16) NULL"); } catch (Exception ignored) {}
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS link_io (
                      id INT AUTO_INCREMENT PRIMARY KEY,
                      serial VARCHAR(8) NULL,
                      pair_code VARCHAR(32) NULL,
                      server_code VARCHAR(16) NOT NULL,
                      role VARCHAR(8) NOT NULL,
                      world VARCHAR(64) NOT NULL,
                      x INT NOT NULL, y INT NOT NULL, z INT NOT NULL,
                      owner_uuid CHAR(36) NOT NULL,
                      owner_name VARCHAR(32) NULL,
                      status VARCHAR(16) NOT NULL DEFAULT 'idle',
                      powered TINYINT NOT NULL DEFAULT 0,
                      level TINYINT NOT NULL DEFAULT 0,
                      logic VARCHAR(16) NOT NULL DEFAULT 'normal',
                      updated_ms BIGINT NOT NULL DEFAULT 0,
                      UNIQUE KEY uniq_io_loc (server_code, world, x, y, z)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            try { s.executeUpdate("ALTER TABLE link_io ADD COLUMN level TINYINT NOT NULL DEFAULT 0"); } catch (Exception ignored) {}
            try { s.executeUpdate("ALTER TABLE link_io ADD COLUMN logic VARCHAR(16) NOT NULL DEFAULT 'normal'"); } catch (Exception ignored) {}
            try { s.executeUpdate("UPDATE link_io SET level=15 WHERE powered<>0 AND level=0"); } catch (Exception ignored) {}
            try { s.executeUpdate("ALTER TABLE link_queue ADD COLUMN nested_keys TEXT NULL"); } catch (Exception ignored) {}
            try { s.executeUpdate("ALTER TABLE link_queue ADD COLUMN return_slots INT NOT NULL DEFAULT 1"); } catch (Exception ignored) {}
            try { s.executeUpdate("ALTER TABLE link_queue ADD COLUMN batch_id VARCHAR(36) NULL"); } catch (Exception ignored) {}
            try { s.executeUpdate("ALTER TABLE link_queue ADD COLUMN parent_id BIGINT NULL"); } catch (Exception ignored) {}
            try { s.executeUpdate("ALTER TABLE link_queue ADD COLUMN row_index INT NULL"); } catch (Exception ignored) {}
            try { s.executeUpdate("ALTER TABLE link_queue ADD COLUMN row_sha256 CHAR(64) NULL"); } catch (Exception ignored) {}
            try { s.executeUpdate("ALTER TABLE link_queue ADD INDEX idx_batch (batch_id)"); } catch (Exception ignored) {}
            try { s.executeUpdate("ALTER TABLE link_chests ADD COLUMN item_filter VARCHAR(128) NOT NULL DEFAULT ''"); } catch (Exception ignored) {}
            try { s.executeUpdate("ALTER TABLE link_chests ADD COLUMN bounce_id INT NOT NULL DEFAULT 0"); } catch (Exception ignored) {}
            try { s.executeUpdate("ALTER TABLE link_chests ADD COLUMN sign_face VARCHAR(16) NULL"); } catch (Exception ignored) {}
            try { s.executeUpdate("ALTER TABLE link_listings ADD COLUMN nested_keys TEXT NULL"); } catch (Exception ignored) {}
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS link_watch (
                      player_uuid CHAR(36) NOT NULL,
                      player_name VARCHAR(32) NOT NULL,
                      kind VARCHAR(8) NOT NULL,
                      node_id INT NOT NULL,
                      created BIGINT NOT NULL,
                      PRIMARY KEY (player_uuid, kind, node_id),
                      INDEX idx_watch_node (kind, node_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            fillSerials(c, "link_chests");
            fillSerials(c, "link_io");
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS link_alerts (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      kind VARCHAR(16) NOT NULL,
                      from_code VARCHAR(16) NOT NULL,
                      from_name VARCHAR(64) NOT NULL,
                      player_name VARCHAR(32) NOT NULL,
                      detail VARCHAR(256) NOT NULL,
                      created BIGINT NOT NULL,
                      INDEX idx_alert_id (id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS link_registry (
                      server_code VARCHAR(16) PRIMARY KEY,
                      digest VARCHAR(40) NOT NULL,
                      item_count INT NOT NULL,
                      payload MEDIUMBLOB,
                      updated BIGINT NOT NULL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS link_io_events (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      pair_code VARCHAR(32) NOT NULL,
                      level TINYINT NOT NULL,
                      event_time_ms BIGINT NOT NULL,
                      INDEX idx_pair_time (pair_code, id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
        }
    }

    public void heartbeat(String code, String name, String shortName, String blurb, String color, String icon) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO link_servers (code, display_name, short_name, blurb, color, icon, last_heartbeat)
                     VALUES (?,?,?,?,?,?, FLOOR(UNIX_TIMESTAMP(NOW(3))*1000))
                     ON DUPLICATE KEY UPDATE display_name=VALUES(display_name),
                     short_name=VALUES(short_name),
                     blurb=VALUES(blurb), color=VALUES(color), icon=VALUES(icon),
                     last_heartbeat=FLOOR(UNIX_TIMESTAMP(NOW(3))*1000)
                     """)) {
            ps.setString(1, code);
            ps.setString(2, name);
            ps.setString(3, shortName == null ? "" : shortName);
            ps.setString(4, blurb == null ? "" : blurb);
            ps.setString(5, color == null || color.isBlank() ? "LIGHT_BLUE" : color);
            ps.setString(6, icon == null || icon.isBlank() ? "TERRACOTTA" : icon);
            ps.executeUpdate();
        }
    }

    public List<Models.ServerRow> servers() throws Exception {
        List<Models.ServerRow> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT code, display_name, short_name, blurb, color, icon, last_heartbeat, FLOOR(UNIX_TIMESTAMP(NOW(3))*1000) AS db_now FROM link_servers")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                out.add(new Models.ServerRow(
                        rs.getString("code"), rs.getString("display_name"),
                        nz(rs, "short_name"), nz(rs, "blurb"), nz(rs, "color"), nz(rs, "icon"),
                        rs.getLong("last_heartbeat"), rs.getLong("db_now")));
            }
        }
        return out;
    }

    public record RegistryRow(String code, String digest, int count, byte[] payload) {}

    /** 各服的物品清单。17000 多个注册名，压缩后几十 KB，靠 digest 判断要不要重传。 */
    public void publishRegistry(String code, String digest, int count, byte[] payload) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO link_registry (server_code, digest, item_count, payload, updated)
                     VALUES (?,?,?,?,?)
                     ON DUPLICATE KEY UPDATE digest=VALUES(digest), item_count=VALUES(item_count),
                       payload=VALUES(payload), updated=VALUES(updated)
                     """)) {
            ps.setString(1, code);
            ps.setString(2, digest);
            ps.setInt(3, count);
            ps.setBytes(4, payload);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /** 只读 digest，用来判断本地缓存过没过期，不拉几十 KB 的正文。 */
    public Map<String, String> registryDigests() throws Exception {
        Map<String, String> out = new java.util.HashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT server_code, digest FROM link_registry")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.put(rs.getString(1), rs.getString(2));
        }
        return out;
    }

    public RegistryRow registryOf(String code) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT digest, item_count, payload FROM link_registry WHERE server_code=?")) {
            ps.setString(1, code);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;
            return new RegistryRow(code, rs.getString(1), rs.getInt(2), rs.getBytes(3));
        }
    }

    public void deleteServer(String code) throws Exception {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM link_servers WHERE code=?")) {
                ps.setString(1, code);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM link_listings WHERE server_code=?")) {
                ps.setString(1, code);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM link_chests WHERE server_code=?")) {
                ps.setString(1, code);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM link_io WHERE server_code=?")) {
                ps.setString(1, code);
                ps.executeUpdate();
            }
        }
    }

    private static void fillSerials(Connection c, String table) {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, serial FROM " + table)) {
            while (rs.next()) {
                int id = rs.getInt("id");
                String ser = rs.getString("serial");
                if (ser != null && ser.trim().length() == 6) continue;
                try (PreparedStatement ps = c.prepareStatement("UPDATE " + table + " SET serial=? WHERE id=?")) {
                    ps.setString(1, Units.code(id));
                    ps.setInt(2, id);
                    ps.executeUpdate();
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static final String CHEST_SELECT = """
            SELECT c.id, c.serial, c.pair_code, c.server_code, c.role, c.world, c.x, c.y, c.z,
                   c.owner_uuid, c.owner_name, c.status, p.serial AS peer_serial, c.item_filter, c.bounce_id, c.sign_face
            FROM link_chests c
            LEFT JOIN link_chests p ON p.pair_code IS NOT NULL AND p.pair_code<>'' AND p.pair_code=c.pair_code AND p.id<>c.id
                AND p.role IN ('TX','RX')
            """;

    private static final String IO_SELECT = """
            SELECT c.id, c.serial, c.pair_code, c.server_code, c.role, c.world, c.x, c.y, c.z,
                   c.owner_uuid, c.owner_name, c.status, c.level, c.updated_ms, c.logic,
                   p.serial AS peer_serial, p.level AS peer_level, p.updated_ms AS peer_updated_ms,
                   p.server_code AS peer_server, FLOOR(UNIX_TIMESTAMP(NOW(3))*1000) AS db_now
            FROM link_io c
            LEFT JOIN link_io p ON p.pair_code IS NOT NULL AND p.pair_code<>'' AND p.pair_code=c.pair_code AND p.id<>c.id
            """;

    private static String nz(ResultSet rs, String col) {
        try {
            String v = rs.getString(col);
            return v == null ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    public long insertListing(UUID seller, String sellerName, String server,
                              String itemKey, String itemName, int amount, double price, String b64,
                              String nestedKeys) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO link_listings (seller_uuid,seller_name,server_code,item_key,item_name,amount,price,created,blob_b64,nested_keys)
                     VALUES (?,?,?,?,?,?,?,?,?,?)
                     """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, seller.toString());
            ps.setString(2, sellerName);
            ps.setString(3, server);
            ps.setString(4, itemKey);
            ps.setString(5, itemName);
            ps.setInt(6, amount);
            ps.setDouble(7, price);
            ps.setLong(8, System.currentTimeMillis());
            ps.setString(9, b64);
            ps.setString(10, nestedKeys);
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            return keys.next() ? keys.getLong(1) : 0;
        }
    }

    public List<Models.Listing> listings(String serverFilter, String query, UUID sellerOnly, int offset, int limit) throws Exception {
        StringBuilder sql = new StringBuilder("""
                SELECT id,seller_uuid,seller_name,server_code,item_key,item_name,amount,price,created,blob_b64,nested_keys
                FROM link_listings WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (serverFilter != null && !serverFilter.isBlank()) {
            sql.append(" AND server_code=?");
            args.add(serverFilter);
        }
        if (sellerOnly != null) {
            sql.append(" AND seller_uuid=?");
            args.add(sellerOnly.toString());
        }
        if (query != null && !query.isBlank()) {
            sql.append(" AND (item_name LIKE ? OR item_key LIKE ? OR seller_name LIKE ?)");
            String q = "%" + query + "%";
            args.add(q); args.add(q); args.add(q);
        }
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        List<Models.Listing> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                Object a = args.get(i);
                if (a instanceof Integer n) ps.setInt(i + 1, n);
                else if (a instanceof Long n) ps.setLong(i + 1, n);
                else ps.setString(i + 1, String.valueOf(a));
            }
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                out.add(readListing(rs));
            }
        }
        return out;
    }

    public Models.Listing listing(long id) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id,seller_uuid,seller_name,server_code,item_key,item_name,amount,price,created,blob_b64,nested_keys
                     FROM link_listings WHERE id=?
                     """)) {
            ps.setLong(1, id);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? readListing(rs) : null;
        }
    }

    public boolean deleteListing(long id) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM link_listings WHERE id=?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    public int deleteListingsOf(String server, UUID uuid) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM link_listings WHERE server_code=? AND seller_uuid=?")) {
            ps.setString(1, server);
            ps.setString(2, uuid.toString());
            return ps.executeUpdate();
        }
    }

    private static Models.Listing readListing(ResultSet rs) throws Exception {
        String b64 = rs.getString("blob_b64");
        byte[] blob = (b64 == null || b64.isBlank()) ? null : java.util.Base64.getDecoder().decode(b64);
        return new Models.Listing(
                rs.getLong("id"),
                UUID.fromString(rs.getString("seller_uuid")),
                rs.getString("seller_name"),
                rs.getString("server_code"),
                rs.getString("item_key"),
                rs.getString("item_name"),
                rs.getInt("amount"),
                rs.getDouble("price"),
                rs.getLong("created"),
                blob,
                nz(rs, "nested_keys"));
    }

    public void setBan(String server, UUID uuid, String reason) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO link_bans (server_code, player_uuid, reason) VALUES (?,?,?)
                     ON DUPLICATE KEY UPDATE reason=VALUES(reason)
                     """)) {
            ps.setString(1, server);
            ps.setString(2, uuid.toString());
            ps.setString(3, reason == null ? "" : reason);
            ps.executeUpdate();
        }
    }

    public void unban(String server, UUID uuid) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM link_bans WHERE server_code=? AND player_uuid=?")) {
            ps.setString(1, server);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    public boolean banned(String server, UUID uuid) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM link_bans WHERE server_code=? AND player_uuid=?")) {
            ps.setString(1, server);
            ps.setString(2, uuid.toString());
            return ps.executeQuery().next();
        }
    }

    public int insertChest(String pair, String server, String role, String world, int x, int y, int z,
                           UUID owner, String ownerName) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO link_chests (pair_code,server_code,role,world,x,y,z,owner_uuid,owner_name,status)
                     VALUES (?,?,?,?,?,?,?,?,?, 'idle')
                     """, Statement.RETURN_GENERATED_KEYS)) {
            if (pair == null || pair.isBlank()) ps.setNull(1, java.sql.Types.VARCHAR);
            else ps.setString(1, pair);
            ps.setString(2, server);
            ps.setString(3, role);
            ps.setString(4, world);
            ps.setInt(5, x);
            ps.setInt(6, y);
            ps.setInt(7, z);
            ps.setString(8, owner.toString());
            ps.setString(9, ownerName == null ? "" : ownerName);
            ps.executeUpdate();
            ResultSet k = ps.getGeneratedKeys();
            int id = k.next() ? k.getInt(1) : 0;
            if (id > 0) assignSerial("link_chests", id);
            return id;
        }
    }

    private void assignSerial(String table, int id) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE " + table + " SET serial=? WHERE id=?")) {
            ps.setString(1, Units.code(id));
            ps.setInt(2, id);
            ps.executeUpdate();
        } catch (Exception ignored) {
        }
    }

    public void pairChests(int idA, int idB, String pairCode) throws Exception {
        Models.ChestRow a = chestById(idA);
        Models.ChestRow b = chestById(idB);
        if (a == null || b == null) throw new Exception("节点不存在");
        if ("BK".equalsIgnoreCase(a.role()) || "BK".equalsIgnoreCase(b.role())) {
            throw new Exception("回退箱不能拿来配对");
        }
        if (a.role() != null && a.role().equalsIgnoreCase(b.role())) {
            throw new Exception("发送只能连接收，不能两端都是" + ESLinkPlugin.roleCn(a.role()));
        }
        releaseChestPair(idA);
        releaseChestPair(idB);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE link_chests SET pair_code=?, status='linked' WHERE id IN (?,?)")) {
            ps.setString(1, pairCode);
            ps.setInt(2, idA);
            ps.setInt(3, idB);
            ps.executeUpdate();
        }
    }

    public void setChestFilter(int id, String filter) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE link_chests SET item_filter=? WHERE id=?")) {
            ps.setString(1, filter == null ? "" : filter.trim());
            ps.setInt(2, id);
            ps.executeUpdate();
        }
    }

    public void setListingPrice(long id, double price) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE link_listings SET price=? WHERE id=?")) {
            ps.setDouble(1, Math.max(0, price));
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public void setChestStatus(int id, String status) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE link_chests SET status=? WHERE id=?")) {
            ps.setString(1, status);
            ps.setInt(2, id);
            ps.executeUpdate();
        }
    }

    public Models.ChestRow chestAt(String server, String world, int x, int y, int z) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     """ + CHEST_SELECT + """
                     WHERE c.server_code=? AND c.world=? AND c.x=? AND c.y=? AND c.z=?
                     """)) {
            ps.setString(1, server);
            ps.setString(2, world);
            ps.setInt(3, x); ps.setInt(4, y); ps.setInt(5, z);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? readChest(rs) : null;
        }
    }

    public Models.ChestRow chestById(int id) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     """ + CHEST_SELECT + """
                     WHERE c.id=?
                     """)) {
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? readChest(rs) : null;
        }
    }

    public List<Models.ChestRow> idleChestsRole(String role) throws Exception {
        List<Models.ChestRow> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     """ + CHEST_SELECT + """
                     WHERE c.role=? AND (c.pair_code IS NULL OR c.pair_code='')
                     """)) {
            ps.setString(1, role);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.add(readChest(rs));
        }
        return out;
    }

    public List<Models.ChestRow> idleChests(String server, String role) throws Exception {
        List<Models.ChestRow> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     """ + CHEST_SELECT + """
                     WHERE c.server_code=? AND c.role=? AND (c.pair_code IS NULL OR c.pair_code='')
                     """)) {
            ps.setString(1, server);
            ps.setString(2, role);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.add(readChest(rs));
        }
        return out;
    }

    public List<Models.ChestRow> chestsOf(UUID owner) throws Exception {
        List<Models.ChestRow> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(CHEST_SELECT + " WHERE c.owner_uuid=? ORDER BY c.id DESC")) {
            ps.setString(1, owner.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.add(readChest(rs));
        }
        return out;
    }

    public void releaseChestPair(int id) throws Exception {
        Models.ChestRow row = chestById(id);
        if (row == null || row.pairCode() == null || row.pairCode().isBlank()) return;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE link_chests SET pair_code=NULL, status='idle' WHERE pair_code=?")) {
            ps.setString(1, row.pairCode());
            ps.executeUpdate();
        }
    }

    public List<Models.ChestRow> chestsOn(String server) throws Exception {
        List<Models.ChestRow> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     """ + CHEST_SELECT + """
                     WHERE c.server_code=?
                     """)) {
            ps.setString(1, server);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.add(readChest(rs));
        }
        return out;
    }

    public void deleteChest(int id) throws Exception {
        Models.ChestRow row = chestById(id);
        if (row != null && "TX".equals(row.role()) && row.bounceId() > 0) {
            deleteChestRow(row.bounceId());
        }
        if (row != null && "BK".equals(row.role()) && row.bounceId() > 0) {
            setBounceLink(row.bounceId(), 0);
        }
        deleteChestRow(id);
    }

    private void deleteChestRow(int id) throws Exception {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement w = c.prepareStatement("DELETE FROM link_watch WHERE kind='chest' AND node_id=?")) {
                w.setInt(1, id);
                w.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM link_chests WHERE id=?")) {
                ps.setInt(1, id);
                ps.executeUpdate();
            }
        }
    }

    public void setSignFace(int id, String face) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE link_chests SET sign_face=? WHERE id=?")) {
            if (face == null || face.isBlank()) ps.setNull(1, java.sql.Types.VARCHAR);
            else ps.setString(1, face.trim().toUpperCase());
            ps.setInt(2, id);
            ps.executeUpdate();
        }
    }

    public void setBounceLink(int id, int bounceId) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE link_chests SET bounce_id=? WHERE id=?")) {
            ps.setInt(1, Math.max(0, bounceId));
            ps.setInt(2, id);
            ps.executeUpdate();
        }
    }

    public void bindBounce(int txId, int bkId) throws Exception {
        setBounceLink(txId, bkId);
        setBounceLink(bkId, txId);
    }

    public int bouncePendingOnPair(String pair) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     """
                     SELECT COALESCE(SUM(CASE WHEN status='pending' THEN LEAST(return_slots,9) ELSE 1 END),0)
                     FROM link_queue WHERE pair_code=? AND status IN ('pending','bounce','unknown')
                     """)) {
            ps.setString(1, pair);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public List<Models.QueueRow> bounceTo(String fromCode) throws Exception {
        List<Models.QueueRow> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id,from_code,to_code,pair_code,item_key,item_name,amount,status,blob_b64,nested_keys
                     FROM link_queue WHERE status IN ('bounce','unknown') AND from_code=? LIMIT 48
                     """)) {
            ps.setString(1, fromCode);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String b64 = rs.getString("blob_b64");
                byte[] blob = (b64 == null || b64.isBlank()) ? null : java.util.Base64.getDecoder().decode(b64);
                out.add(new Models.QueueRow(
                        rs.getLong("id"), rs.getString("from_code"), rs.getString("to_code"),
                        rs.getString("pair_code"), rs.getString("item_key"), rs.getString("item_name"),
                        rs.getInt("amount"), rs.getString("status"), blob, nz(rs, "nested_keys")));
            }
        }
        return out;
    }

    public Models.ChestRow chestByPairRole(String pair, String server, String role) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     """ + CHEST_SELECT + """
                     WHERE c.pair_code=? AND c.server_code=? AND c.role=?
                     """)) {
            ps.setString(1, pair);
            ps.setString(2, server);
            ps.setString(3, role);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? readChest(rs) : null;
        }
    }

    public Models.ChestRow partner(int id, String pair) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     """ + CHEST_SELECT + """
                     WHERE c.pair_code=? AND c.id<>? LIMIT 1
                     """)) {
            ps.setString(1, pair);
            ps.setInt(2, id);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? readChest(rs) : null;
        }
    }

    public int insertIo(String server, String role, String world, int x, int y, int z,
                        UUID owner, String ownerName) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO link_io (pair_code,server_code,role,world,x,y,z,owner_uuid,owner_name,status,powered,level)
                     VALUES (NULL,?,?,?,?,?,?,?,?,'idle',0,0)
                     """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, server);
            ps.setString(2, role);
            ps.setString(3, world);
            ps.setInt(4, x);
            ps.setInt(5, y);
            ps.setInt(6, z);
            ps.setString(7, owner.toString());
            ps.setString(8, ownerName == null ? "" : ownerName);
            ps.executeUpdate();
            ResultSet k = ps.getGeneratedKeys();
            int id = k.next() ? k.getInt(1) : 0;
            if (id > 0) assignSerial("link_io", id);
            return id;
        }
    }

    public void pairIo(int idA, int idB, String pairCode) throws Exception {
        Models.IoRow a = ioById(idA);
        Models.IoRow b = ioById(idB);
        if (a == null || b == null) throw new Exception("节点不存在");
        if (a.role() != null && a.role().equalsIgnoreCase(b.role())) {
            throw new Exception("发送只能连接收，不能两端都是" + ESLinkPlugin.roleCn(a.role()));
        }
        releaseIoPair(idA);
        releaseIoPair(idB);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE link_io SET pair_code=?, status='linked' WHERE id IN (?,?)")) {
            ps.setString(1, pairCode);
            ps.setInt(2, idA);
            ps.setInt(3, idB);
            ps.executeUpdate();
        }
    }

    public void setIoStatus(int id, String status) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE link_io SET status=? WHERE id=?")) {
            ps.setString(1, status);
            ps.setInt(2, id);
            ps.executeUpdate();
        }
    }

    public void setIoLogic(int id, String logic) throws Exception {
        String v = switch (logic == null ? "" : logic) {
            case "invert", "full" -> logic;
            default -> "normal";
        };
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE link_io SET logic=? WHERE id=?")) {
            ps.setString(1, v);
            ps.setInt(2, id);
            ps.executeUpdate();
        }
    }

    public void writeIoLevel(int id, int level) throws Exception {
        int lv = Math.max(0, Math.min(15, level));
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE link_io SET level=?, powered=?, updated_ms=FLOOR(UNIX_TIMESTAMP(NOW(3))*1000) WHERE id=?")) {
            ps.setInt(1, lv);
            ps.setInt(2, lv > 0 ? 1 : 0);
            ps.setInt(3, id);
            ps.executeUpdate();
        }
    }

    public Models.IoRow ioAt(String server, String world, int x, int y, int z) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(IO_SELECT +
                     " WHERE c.server_code=? AND c.world=? AND c.x=? AND c.y=? AND c.z=?")) {
            ps.setString(1, server);
            ps.setString(2, world);
            ps.setInt(3, x); ps.setInt(4, y); ps.setInt(5, z);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? readIo(rs) : null;
        }
    }

    public Models.IoRow ioById(int id) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(IO_SELECT + " WHERE c.id=?")) {
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? readIo(rs) : null;
        }
    }

    public List<Models.IoRow> idleIoRole(String role) throws Exception {
        List<Models.IoRow> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(IO_SELECT +
                     " WHERE c.role=? AND (c.pair_code IS NULL OR c.pair_code='')")) {
            ps.setString(1, role);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.add(readIo(rs));
        }
        return out;
    }

    public List<Models.IoRow> idleIo(String server, String role) throws Exception {
        List<Models.IoRow> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(IO_SELECT +
                     " WHERE c.server_code=? AND c.role=? AND (c.pair_code IS NULL OR c.pair_code='')")) {
            ps.setString(1, server);
            ps.setString(2, role);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.add(readIo(rs));
        }
        return out;
    }

    public List<Models.IoRow> ioOf(UUID owner) throws Exception {
        List<Models.IoRow> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(IO_SELECT + " WHERE c.owner_uuid=? ORDER BY c.id DESC")) {
            ps.setString(1, owner.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.add(readIo(rs));
        }
        return out;
    }

    public void releaseIoPair(int id) throws Exception {
        Models.IoRow row = ioById(id);
        if (row == null || row.pairCode() == null || row.pairCode().isBlank()) return;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE link_io SET pair_code=NULL, status='idle' WHERE pair_code=?")) {
            ps.setString(1, row.pairCode());
            ps.executeUpdate();
        }
    }

    public List<Models.IoRow> ioOn(String server) throws Exception {
        List<Models.IoRow> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(IO_SELECT + " WHERE c.server_code=?")) {
            ps.setString(1, server);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.add(readIo(rs));
        }
        return out;
    }

    public void deleteIo(int id) throws Exception {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement w = c.prepareStatement("DELETE FROM link_watch WHERE kind='io' AND node_id=?")) {
                w.setInt(1, id);
                w.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM link_io WHERE id=?")) {
                ps.setInt(1, id);
                ps.executeUpdate();
            }
        }
    }

    private static Models.IoRow readIo(ResultSet rs) throws Exception {
        int id = rs.getInt("id");
        return new Models.IoRow(
                id, Units.or(nz(rs, "serial"), id), rs.getString("pair_code"), rs.getString("server_code"),
                rs.getString("role"), rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                UUID.fromString(rs.getString("owner_uuid")), rs.getString("status"),
                nz(rs, "owner_name"), clampLv(rs, "level"), rs.getLong("updated_ms"),
                nz(rs, "peer_serial"), clampLv(rs, "peer_level"), rs.getLong("peer_updated_ms"),
                nz(rs, "peer_server"), rs.getLong("db_now"),
                nz(rs, "logic").isBlank() ? "normal" : nz(rs, "logic"));
    }

    private static int clampLv(ResultSet rs, String col) {
        try {
            return Math.max(0, Math.min(15, rs.getInt(col)));
        } catch (Exception e) {
            return 0;
        }
    }

    public int pendingOnPair(String pair) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM link_queue WHERE pair_code=? AND status='pending'")) {
            ps.setString(1, pair);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static Models.ChestRow readChest(ResultSet rs) throws Exception {
        int id = rs.getInt("id");
        return new Models.ChestRow(
                id, Units.or(nz(rs, "serial"), id), rs.getString("pair_code"), rs.getString("server_code"),
                rs.getString("role"), rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                UUID.fromString(rs.getString("owner_uuid")), rs.getString("status"),
                nz(rs, "owner_name"), nz(rs, "peer_serial"), nz(rs, "item_filter"), bounceId(rs), nz(rs, "sign_face"));
    }

    private static int bounceId(ResultSet rs) {
        try {
            return Math.max(0, rs.getInt("bounce_id"));
        } catch (Exception e) {
            return 0;
        }
    }

    public void insertAlert(String kind, String fromCode, String fromName, String playerName, String detail) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO link_alerts (kind, from_code, from_name, player_name, detail, created)
                     VALUES (?,?,?,?,?,?)
                     """)) {
            ps.setString(1, kind);
            ps.setString(2, fromCode);
            ps.setString(3, fromName);
            ps.setString(4, playerName);
            ps.setString(5, detail);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public long maxAlertId() throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COALESCE(MAX(id),0) FROM link_alerts")) {
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    public List<Models.AlertRow> alertsAfter(long afterId, String notFrom, int limit) throws Exception {
        List<Models.AlertRow> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id, kind, from_code, from_name, player_name, detail, created
                     FROM link_alerts WHERE id>? AND from_code<>? ORDER BY id ASC LIMIT ?
                     """)) {
            ps.setLong(1, afterId);
            ps.setString(2, notFrom);
            ps.setInt(3, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                out.add(new Models.AlertRow(
                        rs.getLong("id"), rs.getString("kind"), rs.getString("from_code"),
                        rs.getString("from_name"), rs.getString("player_name"),
                        rs.getString("detail"), rs.getLong("created")));
            }
        }
        return out;
    }

    public void pruneAlerts(long olderThanMs) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM link_alerts WHERE created<?")) {
            ps.setLong(1, System.currentTimeMillis() - olderThanMs);
            ps.executeUpdate();
        }
    }

    public record BatchItem(String itemKey, String itemName, int amount, String b64,
                            String nestedKeys, int returnSlots) {}

    /** 行级校验：对 blob 原文 + 物品标识算 SHA-256。 */
    public static String rowSha256(String b64, String itemKey, int amount, String nestedKeys) {
        String data = (b64 == null ? "" : b64) + "\n"
                + (itemKey == null ? "" : itemKey) + "\n"
                + amount + "\n"
                + (nestedKeys == null ? "" : nestedKeys);
        return sha256(data);
    }

    /** 批次级校验：按行序 0..n-1 汇总各行的 row_sha256。 */
    public static String batchSha256(List<String> rowShas) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rowShas.size(); i++) {
            sb.append(i).append('\n').append(rowShas.get(i)).append('\n');
        }
        return sha256(sb.toString());
    }

    private static String sha256(String data) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void enqueueBatch(String from, String to, String pair, List<BatchItem> items, String batchId) throws Exception {
        if (items == null || items.isEmpty()) return;
        List<String> shas = new ArrayList<>(items.size());
        for (BatchItem it : items) shas.add(rowSha256(it.b64(), it.itemKey(), it.amount(), it.nestedKeys()));
        String batchSha = batchSha256(shas);
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                insertEscrows(c, items);
                try (PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO link_queue
                       (from_code,to_code,pair_code,item_key,item_name,amount,status,blob_b64,created,nested_keys,return_slots,batch_id,parent_id,row_index,row_sha256)
                     VALUES (?,?,?,?,?,?, 'pending', ?,?,?,?,?,NULL,?,?)
                     """)) {
                    for (int i = 0; i < items.size(); i++) {
                        BatchItem it = items.get(i);
                        ps.setString(1, from);
                        ps.setString(2, to);
                        ps.setString(3, pair);
                        ps.setString(4, it.itemKey());
                        ps.setString(5, it.itemName());
                        ps.setInt(6, it.amount());
                        ps.setString(7, it.b64());
                        ps.setLong(8, System.currentTimeMillis());
                        ps.setString(9, it.nestedKeys());
                        ps.setInt(10, Math.max(1, it.returnSlots()));
                        ps.setString(11, batchId);
                        ps.setInt(12, i);
                        ps.setString(13, shas.get(i));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                try (PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO link_batches
                       (batch_id,from_code,to_code,item_count,payload_sha256,status,created)
                     VALUES (?,?,?,?,?,'open',?)
                     """)) {
                    ps.setString(1, batchId);
                    ps.setString(2, from);
                    ps.setString(3, to);
                    ps.setInt(4, items.size());
                    ps.setString(5, batchSha);
                    ps.setLong(6, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                c.commit();
            } catch (Exception e) {
                try { c.rollback(); } catch (Exception ignored) {}
                throw e;
            } finally {
                try { c.setAutoCommit(true); } catch (Exception ignored) {}
            }
        }
    }

    /** 旧签名兼容：单行入队，也顺手生成一个 count=1 的批次校验。 */
    public long enqueue(String from, String to, String pair, String itemKey, String itemName, int amount,
                        String b64, String nestedKeys, int returnSlots, String batchId, Long parentId) throws Exception {
        String useBatch = batchId == null || batchId.isBlank() ? java.util.UUID.randomUUID().toString() : batchId;
        enqueueBatch(from, to, pair, List.of(new BatchItem(itemKey, itemName, amount, b64, nestedKeys, returnSlots)), useBatch);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT MAX(id) FROM link_queue WHERE batch_id=?")) {
            ps.setString(1, useBatch);
            ResultSet r = ps.executeQuery();
            return r.next() ? r.getLong(1) : 0;
        }
    }

    private void insertEscrows(Connection c, List<BatchItem> items) throws Exception {
        for (BatchItem it : items) {
            byte[] payload = java.util.Base64.getDecoder().decode(it.b64());
            List<ItemEnvelope.Escrow> escrows = ItemEnvelope.escrows(payload);
            if (escrows.isEmpty()) continue;
            try (PreparedStatement ep = c.prepareStatement("""
                    INSERT IGNORE INTO link_item_escrow
                      (token,origin_server,payload_b64,status,claim_id,claimed_at,created)
                    VALUES (?,?,?,'active',NULL,0,?)
                    """)) {
                for (ItemEnvelope.Escrow e : escrows) {
                    ep.setString(1, e.token().toString());
                    ep.setString(2, e.originServer());
                    ep.setString(3, java.util.Base64.getEncoder().encodeToString(e.payload()));
                    ep.setLong(4, System.currentTimeMillis());
                    ep.addBatch();
                }
                ep.executeBatch();
            }
        }
    }

    /** 校验 to_code 下所有 open 状态的批次；不通过就整批 quarantine。 */
    public void verifyBatches(String toCode) throws Exception {
        List<String> batchIds = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT DISTINCT q.batch_id
                     FROM link_queue q JOIN link_batches b ON b.batch_id=q.batch_id
                     WHERE q.status='pending' AND q.to_code=? AND b.status='open'
                     LIMIT 16
                     """)) {
            ps.setString(1, toCode);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) batchIds.add(rs.getString(1));
        }
        for (String batchId : batchIds) verifyBatch(batchId);
    }

    private record BatchRow(long id, int index, String sha, String b64,
                            String itemKey, int amount, String nestedKeys) {}

    private void verifyBatch(String batchId) throws Exception {
        int count;
        String batchSha;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT item_count,payload_sha256 FROM link_batches WHERE batch_id=?")) {
            ps.setString(1, batchId);
            ResultSet r = ps.executeQuery();
            if (!r.next()) return;
            count = r.getInt(1);
            batchSha = r.getString(2);
        }
        List<BatchRow> rows = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id,row_index,row_sha256,blob_b64,item_key,amount,nested_keys
                     FROM link_queue WHERE batch_id=? ORDER BY row_index,id
                     """)) {
            ps.setString(1, batchId);
            ResultSet r = ps.executeQuery();
            while (r.next()) {
                rows.add(new BatchRow(r.getLong(1), r.getInt(2), r.getString(3),
                        r.getString(4), r.getString(5), r.getInt(6), nz(r, "nested_keys")));
            }
        }
        boolean ok = rows.size() == count;
        List<String> shas = new ArrayList<>(rows.size());
        if (ok) {
            for (int i = 0; i < rows.size(); i++) {
                BatchRow row = rows.get(i);
                String sha = row.sha() == null ? "" : row.sha().trim();
                String calc = rowSha256(row.b64(), row.itemKey(), row.amount(), row.nestedKeys());
                if (row.index() != i || sha.isBlank() || !sha.equalsIgnoreCase(calc)) {
                    ok = false;
                    break;
                }
                shas.add(sha.toLowerCase(java.util.Locale.ROOT));
            }
        }
        if (ok) {
            ok = batchSha256(shas).equalsIgnoreCase(batchSha == null ? "" : batchSha.trim());
        }
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE link_batches SET status=? WHERE batch_id=?")) {
                    ps.setString(1, ok ? "ok" : "quarantine");
                    ps.setString(2, batchId);
                    ps.executeUpdate();
                }
                if (!ok) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE link_queue SET status='quarantine' WHERE batch_id=? AND status='pending'")) {
                        ps.setString(1, batchId);
                        ps.executeUpdate();
                    }
                }
                c.commit();
            } catch (Exception e) {
                try { c.rollback(); } catch (Exception ignored) {}
                throw e;
            } finally {
                try { c.setAutoCommit(true); } catch (Exception ignored) {}
            }
        }
    }

    public List<Models.QueueRow> pendingTo(String toCode) throws Exception {
        verifyBatches(toCode);
        List<Models.QueueRow> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id,from_code,to_code,pair_code,item_key,item_name,amount,status,blob_b64,nested_keys
                     FROM link_queue q
                     WHERE q.status='pending' AND q.to_code=?
                       AND (q.batch_id IS NULL
                            OR NOT EXISTS (SELECT 1 FROM link_batches b WHERE b.batch_id=q.batch_id)
                            OR EXISTS (SELECT 1 FROM link_batches b WHERE b.batch_id=q.batch_id AND b.status='ok'))
                     LIMIT 48
                     """)) {
            ps.setString(1, toCode);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String b64 = rs.getString("blob_b64");
                byte[] blob = (b64 == null || b64.isBlank()) ? null : java.util.Base64.getDecoder().decode(b64);
                out.add(new Models.QueueRow(
                        rs.getLong("id"), rs.getString("from_code"), rs.getString("to_code"),
                        rs.getString("pair_code"), rs.getString("item_key"), rs.getString("item_name"),
                        rs.getInt("amount"), rs.getString("status"), blob, nz(rs, "nested_keys")));
            }
        }
        return out;
    }

    public void setQueueStatus(long id, String status) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE link_queue SET status=? WHERE id=?")) {
            ps.setString(1, status);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public void completeWithBounces(Models.QueueRow source, List<ItemNbt.PackedChild> rejected) throws Exception {
        if (source == null) return;
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (rejected != null && !rejected.isEmpty()) {
                    try (PreparedStatement ps = c.prepareStatement("""
                            INSERT INTO link_queue
                              (from_code,to_code,pair_code,item_key,item_name,amount,status,blob_b64,created,nested_keys,batch_id,parent_id)
                            VALUES (?,?,?,?,?,?,'bounce',?,?,?,?,?)
                            """)) {
                        for (ItemNbt.PackedChild child : rejected) {
                            ps.setString(1, source.fromCode());
                            ps.setString(2, source.toCode());
                            ps.setString(3, source.pairCode());
                            ps.setString(4, child.key());
                            ps.setString(5, child.name());
                            ps.setInt(6, child.amount());
                            ps.setString(7, java.util.Base64.getEncoder().encodeToString(child.blob()));
                            ps.setLong(8, System.currentTimeMillis());
                            ps.setString(9, child.nestedKeys());
                            ps.setNull(10, java.sql.Types.VARCHAR);
                            ps.setLong(11, source.id());
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE link_queue SET status='delivered' WHERE id=?")) {
                    ps.setLong(1, source.id());
                    ps.executeUpdate();
                }
                c.commit();
            } catch (Exception e) {
                try { c.rollback(); } catch (Exception ignored) {}
                throw e;
            } finally {
                try { c.setAutoCommit(true); } catch (Exception ignored) {}
            }
        }
    }

    public record EscrowClaim(String claimId, Map<UUID, byte[]> payloads) {}

    public EscrowClaim reserveEscrows(List<UUID> tokens, String targetServer) throws Exception {
        if (tokens == null || tokens.isEmpty()) return new EscrowClaim("", Map.of());
        String claimId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        Map<UUID, byte[]> payloads = new LinkedHashMap<>();
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                for (UUID token : tokens) {
                    try (PreparedStatement ps = c.prepareStatement("""
                            SELECT origin_server,payload_b64,status,claimed_at
                            FROM link_item_escrow WHERE token=? FOR UPDATE
                            """)) {
                        ps.setString(1, token.toString());
                        ResultSet rs = ps.executeQuery();
                        if (!rs.next() || !targetServer.equalsIgnoreCase(rs.getString("origin_server"))) {
                            c.rollback();
                            return null;
                        }
                        String status = rs.getString("status");
                        long claimedAt = rs.getLong("claimed_at");
                        boolean stale = "claiming".equals(status) && now - claimedAt > 120_000L;
                        if (!"active".equals(status) && !stale) {
                            c.rollback();
                            return null;
                        }
                        String b64 = rs.getString("payload_b64");
                        payloads.put(token, java.util.Base64.getDecoder().decode(b64));
                    }
                    try (PreparedStatement ps = c.prepareStatement("""
                            UPDATE link_item_escrow
                            SET status='claiming',claim_id=?,claimed_at=?
                            WHERE token=?
                            """)) {
                        ps.setString(1, claimId);
                        ps.setLong(2, now);
                        ps.setString(3, token.toString());
                        ps.executeUpdate();
                    }
                }
                c.commit();
                return new EscrowClaim(claimId, Map.copyOf(payloads));
            } catch (Exception e) {
                try { c.rollback(); } catch (Exception ignored) {}
                throw e;
            } finally {
                try { c.setAutoCommit(true); } catch (Exception ignored) {}
            }
        }
    }

    public void finishEscrows(EscrowClaim claim, boolean delivered) throws Exception {
        if (claim == null || claim.claimId() == null || claim.claimId().isBlank()) return;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(delivered
                     ? """
                       UPDATE link_item_escrow
                       SET status='redeemed',claim_id=NULL,claimed_at=?
                       WHERE claim_id=? AND status='claiming'
                       """
                     : """
                       UPDATE link_item_escrow
                       SET status='active',claim_id=NULL,claimed_at=0
                       WHERE claim_id=? AND status='claiming'
                       """)) {
            if (delivered) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, claim.claimId());
            } else {
                ps.setString(1, claim.claimId());
            }
            ps.executeUpdate();
        }
    }

    public void insertChat(String from, String fromName, UUID uuid, String name, String message,
                           String itemKey, String itemName, Integer itemAmount, String itemB64) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO link_chat (from_code, from_name, player_uuid, player_name, message, created,
                       item_key, item_name, item_amount, item_b64)
                     VALUES (?,?,?,?,?,?,?,?,?,?)
                     """)) {
            ps.setString(1, from);
            ps.setString(2, fromName);
            ps.setString(3, uuid.toString());
            ps.setString(4, name);
            ps.setString(5, message);
            ps.setLong(6, System.currentTimeMillis());
            ps.setString(7, itemKey);
            ps.setString(8, itemName);
            if (itemAmount == null) ps.setNull(9, java.sql.Types.INTEGER);
            else ps.setInt(9, itemAmount);
            ps.setString(10, itemB64);
            ps.executeUpdate();
        }
    }

    public long maxChatId() throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COALESCE(MAX(id),0) FROM link_chat")) {
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    public List<Models.ChatRow> chatAfter(long afterId, String notFrom, int limit) throws Exception {
        List<Models.ChatRow> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id, from_code, from_name, player_uuid, player_name, message, item_key, item_name, item_amount, item_b64
                     FROM link_chat WHERE id>? AND from_code<>? ORDER BY id ASC LIMIT ?
                     """)) {
            ps.setLong(1, afterId);
            ps.setString(2, notFrom);
            ps.setInt(3, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String b64 = rs.getString("item_b64");
                byte[] blob = (b64 == null || b64.isBlank()) ? null : java.util.Base64.getDecoder().decode(b64);
                UUID pu;
                try {
                    pu = UUID.fromString(rs.getString("player_uuid"));
                } catch (Exception e) {
                    pu = null;
                }
                out.add(new Models.ChatRow(
                        rs.getLong("id"), rs.getString("from_code"), rs.getString("from_name"),
                        pu, rs.getString("player_name"), rs.getString("message"),
                        rs.getString("item_key"), rs.getString("item_name"),
                        rs.getInt("item_amount"), blob));
            }
        }
        return out;
    }

    public void pruneChat(long olderThanMs) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM link_chat WHERE created<?")) {
            ps.setLong(1, System.currentTimeMillis() - olderThanMs);
            ps.executeUpdate();
        }
    }

    public boolean watching(UUID player, String kind, int nodeId) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM link_watch WHERE player_uuid=? AND kind=? AND node_id=?")) {
            ps.setString(1, player.toString());
            ps.setString(2, kind);
            ps.setInt(3, nodeId);
            ResultSet rs = ps.executeQuery();
            return rs.next();
        }
    }

    public void setWatch(UUID player, String name, String kind, int nodeId, boolean on) throws Exception {
        if (on) {
            try (Connection c = ds.getConnection();
                 PreparedStatement ps = c.prepareStatement("""
                         INSERT INTO link_watch (player_uuid, player_name, kind, node_id, created)
                         VALUES (?,?,?,?,?)
                         ON DUPLICATE KEY UPDATE player_name=VALUES(player_name)
                         """)) {
                ps.setString(1, player.toString());
                ps.setString(2, name == null ? "" : name);
                ps.setString(3, kind);
                ps.setInt(4, nodeId);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return;
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM link_watch WHERE player_uuid=? AND kind=? AND node_id=?")) {
            ps.setString(1, player.toString());
            ps.setString(2, kind);
            ps.setInt(3, nodeId);
            ps.executeUpdate();
        }
    }

    public List<UUID> watchers(String kind, int nodeId, String pairCode) throws Exception {
        String table = "io".equals(kind) ? "link_io" : "link_chests";
        String sql = """
                SELECT DISTINCT player_uuid FROM link_watch
                WHERE kind=? AND (node_id=? OR (?<>'' AND node_id IN (
                  SELECT id FROM %s WHERE pair_code=? AND pair_code IS NOT NULL AND pair_code<>''
                )))
                """.formatted(table);
        List<UUID> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, kind);
            ps.setInt(2, nodeId);
            ps.setString(3, pairCode == null ? "" : pairCode);
            ps.setString(4, pairCode == null ? "" : pairCode);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                try {
                    out.add(UUID.fromString(rs.getString(1)));
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }

    public List<Models.WatchRow> watchesOf(UUID player) throws Exception {
        List<Models.WatchRow> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT w.kind, w.node_id,
                       COALESCE(c.serial, i.serial) AS serial,
                       COALESCE(c.role, i.role) AS role,
                       COALESCE(c.server_code, i.server_code) AS server_code,
                       COALESCE(c.pair_code, i.pair_code) AS pair_code,
                       COALESCE(c.status, i.status) AS status,
                       COALESCE(c.owner_name, i.owner_name) AS owner_name
                     FROM link_watch w
                     LEFT JOIN link_chests c ON w.kind='chest' AND w.node_id=c.id
                     LEFT JOIN link_io i ON w.kind='io' AND w.node_id=i.id
                     WHERE w.player_uuid=?
                     ORDER BY w.created DESC
                     """)) {
            ps.setString(1, player.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                int id = rs.getInt("node_id");
                String serial = nz(rs, "serial");
                out.add(new Models.WatchRow(
                        rs.getString("kind"), id, Units.or(serial, id),
                        nz(rs, "role"), nz(rs, "server_code"), nz(rs, "pair_code"),
                        nz(rs, "status"), nz(rs, "owner_name")));
            }
        }
        return out;
    }

    public void insertIoEvent(String pairCode, int level, long timeMs) throws Exception {
        if (pairCode == null || pairCode.isBlank()) return;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO link_io_events (pair_code, level, event_time_ms) VALUES (?, ?, ?)")) {
            ps.setString(1, pairCode);
            ps.setInt(2, Math.max(0, Math.min(15, level)));
            ps.setLong(3, timeMs);
            ps.executeUpdate();
        }
    }

    public long maxIoEventId(String pairCode) throws Exception {
        if (pairCode == null || pairCode.isBlank()) return 0;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COALESCE(MAX(id), 0) FROM link_io_events WHERE pair_code=?")) {
            ps.setString(1, pairCode);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    public List<Models.IoEvent> ioEventsAfter(String pairCode, long afterId) throws Exception {
        List<Models.IoEvent> out = new ArrayList<>();
        if (pairCode == null || pairCode.isBlank()) return out;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, level, event_time_ms FROM link_io_events WHERE pair_code=? AND id>? ORDER BY id ASC")) {
            ps.setString(1, pairCode);
            ps.setLong(2, afterId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                out.add(new Models.IoEvent(rs.getLong("id"), rs.getInt("level"), rs.getLong("event_time_ms")));
            }
        }
        return out;
    }

    public void pruneIoEvents(long olderThanMs) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM link_io_events WHERE event_time_ms<?")) {
            ps.setLong(1, System.currentTimeMillis() - olderThanMs);
            ps.executeUpdate();
        }
    }
}
