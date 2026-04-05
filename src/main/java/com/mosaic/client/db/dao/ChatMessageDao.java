package com.mosaic.client.db.dao;

import com.mosaic.client.db.DatabaseManager;
import com.mosaic.client.db.model.ChatMessage;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Data access object for the Chat_History table.
 */
public class ChatMessageDao {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private Connection getConnection() {
        return DatabaseManager.getInstance().getConnection();
    }

    /**
     * Inserts a new message and returns it with the generated msg_id.
     */
    public ChatMessage create(ChatMessage msg) throws SQLException {
        String sql = "INSERT INTO Chat_History (session_id, timestamp, role, content, used_adapter) "
                   + "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, msg.getSessionId());
            ps.setString(2, msg.getTimestamp().format(FMT));
            ps.setString(3, msg.getRole());
            ps.setString(4, msg.getContent());
            ps.setString(5, msg.getUsedAdapter()); // nullable
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    msg.setMsgId(keys.getInt(1));
                }
            }
        }
        return msg;
    }

    /**
     * Retrieves all messages for a session, ordered by timestamp ascending (context loading).
     */
    public List<ChatMessage> findBySessionId(int sessionId) throws SQLException {
        String sql = "SELECT msg_id, session_id, timestamp, role, content, used_adapter "
                   + "FROM Chat_History WHERE session_id = ? ORDER BY timestamp ASC";
        List<ChatMessage> messages = new ArrayList<>();
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    messages.add(mapRow(rs));
                }
            }
        }
        return messages;
    }

    /**
     * Deletes all messages for a given session.
     */
    public void deleteBySessionId(int sessionId) throws SQLException {
        String sql = "DELETE FROM Chat_History WHERE session_id = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, sessionId);
            ps.executeUpdate();
        }
    }

    private ChatMessage mapRow(ResultSet rs) throws SQLException {
        ChatMessage msg = new ChatMessage();
        msg.setMsgId(rs.getInt("msg_id"));
        msg.setSessionId(rs.getInt("session_id"));
        msg.setTimestamp(LocalDateTime.parse(rs.getString("timestamp"), FMT));
        msg.setRole(rs.getString("role"));
        msg.setContent(rs.getString("content"));
        msg.setUsedAdapter(rs.getString("used_adapter"));
        return msg;
    }
}
