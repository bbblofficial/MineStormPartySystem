package com.minestorm.party.bukkit;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Storage backend. SQLite (local file) or MySQL (shared across servers).
 *
 * With type: mysql the plugin connects using only host / database /
 * username / password from config.yml. If the database does not exist
 * it is created automatically along with the required tables and indexes.
 *
 * All JDBC work runs on ONE dedicated thread, so the server thread never
 * blocks on disk I/O (except the initial load) and the single connection
 * is never used concurrently.
 */
public class Database {

    public enum Type { SQLITE, MYSQL }

    public interface Work<T> { T run(Connection c) throws Exception; }

    private final MineStormParty plugin;
    private final Type type;

    // SQLite
    private final File file;

    // MySQL
    private final String jdbcUrl;        // full DB url (after DB exists)
    private final String serverUrl;      // url without db name, used to CREATE DATABASE
    private final String dbName;
    private final String username;
    private final String password;

    private Connection conn;             // only touched from the executor thread
    private HikariDataSource pool;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "MineStormParty-DB");
            t.setDaemon(true);
            return t;
        }
    });

    public Database(MineStormParty plugin) {
        this.plugin = plugin;
        String t = plugin.getConfig().getString("database.type", "sqlite");
        this.type = t.equalsIgnoreCase("mysql") ? Type.MYSQL : Type.SQLITE;

        if (type == Type.SQLITE) {
            String name = plugin.getConfig().getString("database.file", "parties.db");
            this.file = new File(plugin.getDataFolder(), name);
            this.jdbcUrl = "jdbc:sqlite:" + file.getAbsolutePath();
            this.serverUrl = null;
            this.dbName = null;
            this.username = null;
            this.password = null;
        } else {
            this.file = null;
            String host = plugin.getConfig().getString("database.host", "127.0.0.1");
            int port = plugin.getConfig().getInt("database.port", 3306);
            this.dbName = plugin.getConfig().getString("database.database", "minestormparty");
            this.username = plugin.getConfig().getString("database.username", "root");
            this.password = plugin.getConfig().getString("database.password", "");
            boolean useSSL = plugin.getConfig().getBoolean("database.useSSL", false);
            String tz = plugin.getConfig().getString("database.serverTimezone", "UTC");

            String base = "jdbc:mysql://" + host + ":" + port;
            String opts = "?useSSL=" + useSSL
                    + "&serverTimezone=" + tz
                    + "&characterEncoding=utf8"
                    + "&useUnicode=true"
                    + "&allowPublicKeyRetrieval=true"
                    + "&autoReconnect=true"
                    + "&createDatabaseIfNotExist=true";
            this.serverUrl = base + "/" + opts;
            this.jdbcUrl = base + "/" + dbName + opts;
        }
    }

    public Type getType() { return type; }

    // -------- public API (same shape as before) --------

    public void init() throws Exception {
        query(new Work<Void>() {
            @Override public Void run(Connection c) throws SQLException {
                String engineSuffix = type == Type.MYSQL
                        ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
                        : "";
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS msp_parties ("
                            + " party_id VARCHAR(36) PRIMARY KEY,"
                            + " leader_uuid VARCHAR(36) NOT NULL,"
                            + " leader_name VARCHAR(16) NOT NULL,"
                            + " color VARCHAR(1) NOT NULL DEFAULT 'b',"
                            + " created BIGINT NOT NULL)" + engineSuffix);
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS msp_members ("
                            + " uuid VARCHAR(36) PRIMARY KEY,"
                            + " party_id VARCHAR(36) NOT NULL,"
                            + " name VARCHAR(16) NOT NULL,"
                            + " INDEX idx_msp_members_party (party_id),"
                            + " INDEX idx_msp_members_uuid (uuid))" + engineSuffix);
                }
                return null;
            }
        });
    }

    /** Synchronous (blocks the caller until finished). */
    public <T> T query(final Work<T> w) throws Exception {
        return exec.submit(new Callable<T>() {
            @Override public T call() throws Exception { return w.run(conn()); }
        }).get();
    }

    /** Fire-and-forget on the DB thread. */
    public void execute(final Work<?> w) {
        try {
            exec.execute(new Runnable() {
                @Override public void run() {
                    try { w.run(conn()); }
                    catch (Exception ex) { plugin.getLogger().log(Level.SEVERE, "Database error", ex); }
                }
            });
        } catch (RejectedExecutionException ignored) { }
    }

    public void close() {
        exec.shutdown();
        try { exec.awaitTermination(10, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (pool != null && !pool.isClosed()) pool.close();
        try { if (conn != null && !conn.isClosed()) conn.close(); }
        catch (SQLException ignored) { }
    }

    // -------- internals --------

    private Connection conn() throws Exception {
        if (conn == null || conn.isClosed()) {
            if (type == Type.SQLITE) {
                File dir = file.getParentFile();
                if (dir != null && !dir.exists()) dir.mkdirs();
                Class.forName("org.sqlite.JDBC");
                conn = DriverManager.getConnection(jdbcUrl);
                try (Statement st = conn.createStatement()) {
                    st.execute("PRAGMA busy_timeout = 5000");
                    st.execute("PRAGMA foreign_keys = ON");
                }
            } else {
                ensureDatabaseExists();
                HikariConfig cfg = new HikariConfig();
                cfg.setJdbcUrl(jdbcUrl);
                cfg.setUsername(username);
                cfg.setPassword(password);
                cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");
                cfg.setMaximumPoolSize(plugin.getConfig().getInt("database.poolSize", 10));
                cfg.setMinimumIdle(1);
                cfg.setConnectionTimeout(10000L);
                cfg.setIdleTimeout(600000L);
                cfg.setMaxLifetime(1800000L);
                cfg.setPoolName("MineStormParty-MySQL");
                cfg.setLeakDetectionThreshold(60000L);
                pool = new HikariDataSource(cfg);
                conn = pool.getConnection();
            }
        }
        return conn;
    }

    /** Connect to the server (no db) and CREATE DATABASE if needed. */
    private void ensureDatabaseExists() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException ex) {
            throw new SQLException("MySQL driver not found", ex);
        }
        try (Connection c = DriverManager.getConnection(serverUrl, username, password);
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + dbName + "` "
                    + "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        } catch (SQLException ex) {
            plugin.getLogger().warning(
                "Could not auto-create database '" + dbName + "': " + ex.getMessage());
            // continue; the DB probably exists and we simply lack CREATE
        }
    }
}
