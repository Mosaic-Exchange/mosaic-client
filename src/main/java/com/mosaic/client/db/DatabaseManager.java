package com.mosaic.client.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Owns the client's SQLite connection and creates the schema on first use.
 */
public class DatabaseManager {

    private static final String DB_DIR = System.getProperty("user.home") + "/.mosaic";
    private static final String DB_URL = "jdbc:sqlite:" + DB_DIR + "/mosaic.db";
    private static final System.Logger LOGGER = System.getLogger(DatabaseManager.class.getName());

    private static DatabaseManager instance;
    private Connection connection;

    private DatabaseManager() {}

    public static synchronized DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager();
        }
        return instance;
    }

    public void initialize() throws SQLException {
        try {
            Path dir = Paths.get(DB_DIR);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
        } catch (IOException e) {
            throw new SQLException("Failed to create database directory: " + DB_DIR, e);
        }

        connection = DriverManager.getConnection(DB_URL);
        connection.setAutoCommit(true);

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

    public void shutdown() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                LOGGER.log(System.Logger.Level.WARNING, "Failed to close database connection.", e);
            } finally {
                connection = null;
            }
        }
    }
}
