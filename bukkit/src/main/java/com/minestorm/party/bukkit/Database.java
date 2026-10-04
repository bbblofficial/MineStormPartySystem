package com.minestorm.party.bukkit;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

public class Database {

    private final MineStormParty plugin;
    private final File file;
    private Connection conn;

    public Database(MineStormParty plugin) {
        this.plugin = plugin;
        String name = plugin.getConfig().getString("database.file", "guilds.db");
        this.file = new File(plugin.getDataFolder(), name);
    }

    public synchronized Connection connection() throws SQLException {
        if (conn == null || conn.isClosed()) {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            try { Class.forName("org.sqlite.JDBC"); }
            catch (ClassNotFoundException ex) {
                try { Class.forName("com.minestorm.party.libs.sqlite.JDBC"); }
                catch (ClassNotFoundException ignored) {}
            }
            conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement st = conn.createStatement()) { st.executeUpdate("PRAGMA foreign_keys = ON"); }
            createSchema();
        }
        return conn;
    }

    private void createSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS parties (" +
                " leader_uuid TEXT PRIMARY KEY," +
                " leader_name TEXT NOT NULL," +
                " color TEXT NOT NULL DEFAULT 'b'," +
                " created INTEGER NOT NULL" +
                ");"
            );
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS party_members (" +
                " leader_uuid TEXT NOT NULL," +
                " uuid TEXT PRIMARY KEY," +
                " name TEXT NOT NULL," +
                " joined INTEGER NOT NULL DEFAULT 0," +
                " FOREIGN KEY (leader_uuid) REFERENCES parties(leader_uuid) ON DELETE CASCADE" +
                ");"
            );
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS invites (" +
                " leader_uuid TEXT NOT NULL," +
                " target_uuid TEXT NOT NULL," +
                " target_name TEXT NOT NULL," +
                " expires INTEGER NOT NULL," +
                " PRIMARY KEY (leader_uuid, target_uuid)" +
                ");"
            );
        }
    }

    public PreparedStatement prep(String sql) throws SQLException { return connection().prepareStatement(sql); }
    public synchronized void close() { try { if (conn != null && !conn.isClosed()) conn.close(); } catch (SQLException ignored) {} }
}
