package com.mosaic.client.db.dao;

import com.mosaic.client.db.DatabaseManager;
import com.mosaic.client.db.model.ChatSession;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Data access object for the Chat_Session table.
 */
public class ChatSessionDao {

    private Connection getConnection() {
        return DatabaseManager.getInstance().getConnection();
    }

    /**
     * Creates a new chat session and returns it with the generated session_id.
     */
    public ChatSession create(String topic) throws SQLException {
        String sql = "INSERT INTO Chat_Session (topic) VALUES (?)";
        try (PreparedStatement ps = getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, topic);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return new ChatSession(keys.getInt(1), topic);
                }
            }
        }
        throw new SQLException("Failed to create chat session");
    }

    /**
     * Returns all chat sessions ordered by session_id descending (newest first).
     */
    public List<ChatSession> findAll() throws SQLException {
        String sql = "SELECT session_id, topic FROM Chat_Session ORDER BY session_id DESC";
        List<ChatSession> sessions = new ArrayList<>();
        try (Statement stmt = getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                sessions.add(new ChatSession(
                    rs.getInt("session_id"),
                    rs.getString("topic")
                ));
            }
        }
        return sessions;
    }

    /**
     * Finds a single session by its ID, or null if not found.
     */
    public ChatSession findById(int sessionId) throws SQLException {
        String sql = "SELECT session_id, topic FROM Chat_Session WHERE session_id = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new ChatSession(rs.getInt("session_id"), rs.getString("topic"));
                }
            }
        }
        return null;
    }

    /**
     * Updates the topic of an existing session.
     */
    public void updateTopic(int sessionId, String newTopic) throws SQLException {
        String sql = "UPDATE Chat_Session SET topic = ? WHERE session_id = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, newTopic);
            ps.setInt(2, sessionId);
            ps.executeUpdate();
        }
    }

    /**
     * Deletes a session and all its messages (via ON DELETE CASCADE).
     */
    public void delete(int sessionId) throws SQLException {
        String sql = "DELETE FROM Chat_Session WHERE session_id = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, sessionId);
            ps.executeUpdate();
        }
    }
}
