package com.mosaic.client.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Manages the SQLite database connection and schema initialization.
 * The database file is stored at {@code <dataDir>/mosaic.db}.
 */
public class DatabaseManager {

    private static DatabaseManager instance;
    private Connection connection;

    private DatabaseManager() {}

    public static synchronized DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager();
        }
        return instance;
    }

    /**
     * Opens the database connection and creates tables if they do not exist.
     *
     * @param mosaicDir directory where {@code mosaic.db} will be stored
     */
    public void initialize(Path mosaicDir) throws SQLException {
        try {
            if (!Files.exists(mosaicDir)) {
                Files.createDirectories(mosaicDir);
            }
        } catch (Exception e) {
            throw new SQLException("Failed to create database directory: " + e.getMessage(), e);
        }

        String dbUrl = "jdbc:sqlite:" + mosaicDir.resolve("mosaic.db");
        connection = DriverManager.getConnection(dbUrl);
        connection.setAutoCommit(true);

        // Enable WAL mode for better concurrent read performance
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA foreign_keys=ON");
        }

        createTables();
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS Chat_Session (
                    session_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    topic      TEXT NOT NULL DEFAULT 'New Chat'
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS Local_Adapters (
                    adapter_id TEXT PRIMARY KEY,
                    name       TEXT NOT NULL,
                    domain     TEXT,
                    file_path  TEXT,
                    file_hash  TEXT,
                    size_mb    INTEGER
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS Chat_History (
                    msg_id       INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id   INTEGER NOT NULL,
                    timestamp    TEXT    NOT NULL,
                    role         TEXT    NOT NULL CHECK(role IN ('User', 'Assistant')),
                    content      TEXT    NOT NULL,
                    used_adapter TEXT,
                    FOREIGN KEY (session_id)   REFERENCES Chat_Session(session_id) ON DELETE CASCADE,
                    FOREIGN KEY (used_adapter)  REFERENCES Local_Adapters(adapter_id)
                )
            """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_chat_history_session
                    ON Chat_History(session_id, timestamp ASC)
            """);
        }
    }

    public Connection getConnection() {
        return connection;
    }

    /**
     * Closes the database connection. Call on application shutdown.
     */
    public void shutdown() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }
}
