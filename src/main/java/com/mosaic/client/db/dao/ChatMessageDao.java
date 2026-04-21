package com.mosaic.client.db.dao;

import com.mosaic.client.db.DatabaseManager;
import com.mosaic.client.db.model.ChatMessage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class ChatMessageDao {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private Connection getConnection() {
        return DatabaseManager.getInstance().getConnection();
    }

    public ChatMessage create(ChatMessage msg) throws SQLException {
        String sql = "INSERT INTO Chat_History (session_id, timestamp, role, content, used_adapter) "
                   + "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, msg.getSessionId());
            ps.setString(2, msg.getTimestamp().format(FMT));
            ps.setString(3, msg.getRole());
            ps.setString(4, msg.getContent());
            ps.setString(5, msg.getUsedAdapter());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    msg.setMsgId(keys.getInt(1));
                }
            }
        }
        return msg;
    }

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
