package com.minestorm.party.bukkit;

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
 * SQLite access. All JDBC work runs on ONE dedicated thread, so the server thread
 * never blocks on disk I/O (except for the initial load) and the single
 * connection is never used concurrently.
 */
public class Database {

    public interface Work<T> { T run(Connection c) throws Exception; }

    private final MineStormParty plugin;
    private final File file;
    private Connection conn; // only touched from the executor thread

    private final ExecutorService exec = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "MineStormParty-DB");
            t.setDaemon(true);
            return t;
        }
    });

    public Database(MineStormParty plugin) {
        this.plugin = plugin;
        String name = plugin.getConfig().getString("database.file", "guilds.db");
        this.file = new File(plugin.getDataFolder(), name);
    }

    private Connection conn() throws Exception {
        if (conn == null || conn.isClosed()) {
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            Class.forName("org.sqlite.JDBC");
            conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA busy_timeout = 5000");
            }
        }
        return conn;
    }

    public void init() throws Exception {
        query(new Work<Void>() {
            @Override public Void run(Connection c) throws SQLException {
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS msp_parties ("
                            + " party_id TEXT PRIMARY KEY,"
                            + " leader_uuid TEXT NOT NULL,"
                            + " leader_name TEXT NOT NULL,"
                            + " color TEXT NOT NULL DEFAULT 'b',"
                            + " created INTEGER NOT NULL)");
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS msp_members ("
                            + " uuid TEXT PRIMARY KEY,"
                            + " party_id TEXT NOT NULL,"
                            + " name TEXT NOT NULL)");
                    st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_msp_members_party ON msp_members(party_id)");
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
        try { if (conn != null && !conn.isClosed()) conn.close(); }
        catch (SQLException ignored) { }
    }
}
