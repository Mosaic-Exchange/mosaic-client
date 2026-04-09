package com.mosaic.client.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * A test-only variant of DatabaseManager that uses an in-memory SQLite database.
 * This avoids touching the real user database during testing.
 */
public class TestDatabaseManager {

    private Connection connection;

    public void initialize() throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        connection.setAutoCommit(true);

        try (Statement stmt = connection.createStatement()) {
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
                e.printStackTrace();
            }
        }
    }
}
