package com.mosaic.client.db.model;

import java.time.LocalDateTime;

/**
 * Represents a single chat message (maps to the Chat_History table).
 */
public class ChatMessage {

    private int msgId;
    private int sessionId;
    private LocalDateTime timestamp;
    private String role;      // "User" or "Assistant"
    private String content;
    private String usedAdapter;  // adapter_id (UUID string), nullable

    public ChatMessage() {}

    public ChatMessage(int sessionId, String role, String content, String usedAdapter) {
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
        this.usedAdapter = usedAdapter;
        this.timestamp = LocalDateTime.now();
    }

    public int getMsgId() { return msgId; }
    public void setMsgId(int msgId) { this.msgId = msgId; }

    public int getSessionId() { return sessionId; }
    public void setSessionId(int sessionId) { this.sessionId = sessionId; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getUsedAdapter() { return usedAdapter; }
    public void setUsedAdapter(String usedAdapter) { this.usedAdapter = usedAdapter; }
}
