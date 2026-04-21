package com.mosaic.client.db.model;

public class ChatSession {

    private int sessionId;
    private String topic;

    public ChatSession() {}

    public ChatSession(int sessionId, String topic) {
        this.sessionId = sessionId;
        this.topic = topic;
    }

    public int getSessionId() { return sessionId; }
    public void setSessionId(int sessionId) { this.sessionId = sessionId; }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    @Override
    public String toString() {
        return topic;
    }
}
