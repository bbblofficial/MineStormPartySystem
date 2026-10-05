package com.minestorm.party.bukkit;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * All JDBC work runs on ONE dedicated thread, so writes are applied in the
 * exact order they were queued and the server thread never blocks on I/O
 * (except for the few explicit blocking calls).
 *
 * MSP-FIXER v2:
 *  - MySQL: a connection is borrowed from the Hikari pool for every task
 *    instead of being held forever (a held connection silently dies after
 *    wait_timeout / a network blip and every later write was lost).
 *  - Pool is created lazily and tolerates the database being down at
 *    start-up; schema creation is retried until it succeeds.
 *  - connect / socket timeouts so a dead socket can never freeze the DB thread.
 *  - Every task is retried once on SQL errors.
 *  - SQLite schema no longer uses MySQL-only inline INDEX syntax.
 *  - Error logging is throttled (no log spam while the DB is down).
 */
public class Database {

    public enum Type { SQLITE, MYSQL }

    public interface Work<T> { T run(Connection c) throws Exception; }

    private final MineStormParty plugin;
    private final Type type;

    // SQLite
    private final File file;

    // MySQL
    private final String jdbcUrl;        // full DB url
    private final String serverUrl;      // url without db name, used to CREATE DATABASE
    private final String dbName;
    private final String username;
    private final String password;

    private Connection sqliteConn;       // only touched from the executor thread
    private HikariDataSource pool;       // only touched from the executor thread
    private boolean schemaReady;         // only touched from the executor thread

    private volatile long lastErrorLog = 0L;
    private volatile int suppressed = 0;

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
                    + "&connectTimeout=10000"
                    + "&socketTimeout=30000"
                    + "&createDatabaseIfNotExist=true";
            this.serverUrl = base + "/" + opts;
            this.jdbcUrl = base + "/" + dbName + opts;
        }
    }

    public Type getType() { return type; }

    // -------- public API --------

    /** Forces a first connection + schema creation. Throws if the database is unreachable. */
    public void init() throws Exception {
        query(new Work<Void>() {
            @Override public Void run(Connection c) { return null; }
        });
    }

    /** Synchronous (blocks the caller until finished). */
    public <T> T query(final Work<T> w) throws Exception {
        return query(w, 0L);
    }

    /** Synchronous with a timeout (0 = wait forever). */
    public <T> T query(final Work<T> w, long timeoutMs) throws Exception {
        Future<T> f = exec.submit(new Callable<T>() {
            @Override public T call() throws Exception { return run(w); }
        });
        try {
            return timeoutMs > 0 ? f.get(timeoutMs, TimeUnit.MILLISECONDS) : f.get();
        } catch (ExecutionException ee) {
            Throwable c = ee.getCause();
            if (c instanceof Exception) throw (Exception) c;
            throw ee;
        }
    }

    /** Fire-and-forget on the DB thread (ordered). */
    public void execute(final Work<?> w) {
        try {
            exec.execute(new Runnable() {
                @Override public void run() {
                    try { Database.this.run(w); }
                    catch (Throwable ex) { logError("Database write failed (data may not be saved!)", ex); }
                }
            });
        } catch (RejectedExecutionException ex) {
            plugin.getLogger().warning("Database is shutting down - a write was dropped.");
        }
    }

    /** Blocks until everything queued so far has been executed. */
    public void flush(long timeoutMs) {
        try {
            query(new Work<Void>() {
                @Override public Void run(Connection c) { return null; }
            }, timeoutMs);
        } catch (Throwable ignored) { }
    }

    public void close() {
        exec.shutdown();
        try {
            if (!exec.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Database queue did not finish within 10s - some writes may be lost.");
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        try { if (pool != null && !pool.isClosed()) pool.close(); } catch (Throwable ignored) { }
        try { if (sqliteConn != null && !sqliteConn.isClosed()) sqliteConn.close(); } catch (SQLException ignored) { }
    }

    // -------- internals --------

    /** Runs a task with one automatic retry. Executor thread only. */
    private <T> T run(Work<T> w) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (type == Type.SQLITE) {
                    Connection c = sqlite();
                    ensureSchema(c);
                    return w.run(c);
                }
                try (Connection c = mysqlPool().getConnection()) {
                    ensureSchema(c);
                    return w.run(c);
                }
            } catch (Exception ex) {
                last = ex;
                if (type == Type.SQLITE) {
                    try { if (sqliteConn != null) sqliteConn.close(); } catch (Throwable ignored) { }
                    sqliteConn = null;
                }
                if (attempt == 0) {
                    try { Thread.sleep(400L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw ex; }
                }
            }
        }
        throw last;
    }

    private Connection sqlite() throws Exception {
        if (sqliteConn == null || sqliteConn.isClosed()) {
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            Class.forName("org.sqlite.JDBC");
            sqliteConn = DriverManager.getConnection(jdbcUrl);
            try (Statement st = sqliteConn.createStatement()) {
                st.execute("PRAGMA busy_timeout = 5000");
            }
        }
        return sqliteConn;
    }

    private HikariDataSource mysqlPool() throws Exception {
        if (pool == null || pool.isClosed()) {
            ensureDatabaseExists();
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(jdbcUrl);
            cfg.setUsername(username);
            cfg.setPassword(password);
            cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");
            cfg.setMaximumPoolSize(Math.max(2, plugin.getConfig().getInt("database.poolSize", 10)));
            cfg.setMinimumIdle(1);
            cfg.setConnectionTimeout(10000L);
            cfg.setValidationTimeout(5000L);
            cfg.setIdleTimeout(600000L);
            cfg.setKeepaliveTime(120000L);   // keeps idle connections alive (wait_timeout)
            cfg.setMaxLifetime(1500000L);    // 25 min, must stay below MySQL wait_timeout
            cfg.setInitializationFailTimeout(-1L); // do not fail if the DB is down right now
            cfg.setPoolName("MineStormParty-MySQL");
            pool = new HikariDataSource(cfg);
        }
        return pool;
    }

    /** Creates tables / indexes on the first working connection. Executor thread only. */
    private void ensureSchema(Connection c) throws SQLException {
        if (schemaReady) return;
        boolean mysql = type == Type.MYSQL;
        String engine = mysql ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci" : "";
        try (Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS msp_parties ("
                    + " party_id VARCHAR(36) PRIMARY KEY,"
                    + " leader_uuid VARCHAR(36) NOT NULL,"
                    + " leader_name VARCHAR(32) NOT NULL,"
                    + " color VARCHAR(1) NOT NULL DEFAULT 'b',"
                    + " created BIGINT NOT NULL)" + engine);

            st.executeUpdate("CREATE TABLE IF NOT EXISTS msp_members ("
                    + " uuid VARCHAR(36) PRIMARY KEY,"
                    + " party_id VARCHAR(36) NOT NULL,"
                    + " name VARCHAR(32) NOT NULL"
                    + (mysql ? ", INDEX idx_msp_members_party (party_id)" : "")
                    + ")" + engine);

            // Pending invites live in the database so they work between servers
            // even when the proxy relay is missing / has nobody to carry a message.
            st.executeUpdate("CREATE TABLE IF NOT EXISTS msp_invites ("
                    + " party_id VARCHAR(36) NOT NULL,"
                    + " target_name VARCHAR(32) NOT NULL,"
                    + " leader_uuid VARCHAR(36) NOT NULL,"
                    + " leader_name VARCHAR(32) NOT NULL,"
                    + " color VARCHAR(1) NOT NULL DEFAULT 'b',"
                    + " expires BIGINT NOT NULL,"
                    + " PRIMARY KEY (party_id, target_name)"
                    + (mysql ? ", INDEX idx_msp_invites_target (target_name)" : "")
                    + ")" + engine);

            if (!mysql) {
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_msp_members_party ON msp_members(party_id)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_msp_invites_target ON msp_invites(target_name)");
            }
        }
        schemaReady = true;
    }

    /** Connect to the server (no db) and CREATE DATABASE if needed. */
    private void ensureDatabaseExists() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException ex) {
            plugin.getLogger().severe("MySQL driver not found in the jar!");
            return;
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

    /** Logs the first error immediately, then at most one every 30 seconds. */
    void logError(String msg, Throwable ex) {
        long now = System.currentTimeMillis();
        if (now - lastErrorLog > 30000L) {
            lastErrorLog = now;
            int s = suppressed;
            suppressed = 0;
            plugin.getLogger().log(Level.SEVERE, msg + (s > 0 ? " (+" + s + " similar errors suppressed)" : ""), ex);
        } else {
            suppressed++;
        }
    }
}
