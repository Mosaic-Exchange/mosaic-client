package com.mosaic.client.db.dao;

import com.mosaic.client.db.TestDatabaseManager;
import com.mosaic.client.db.model.ChatMessage;
import com.mosaic.client.db.model.ChatSession;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ChatMessageDao using an in-memory SQLite database.
 */
class ChatMessageDaoTest {

    private static TestDatabaseManager testDb;
    private ChatSessionDao sessionDao;
    private ChatMessageDao messageDao;

    @BeforeAll
    static void setUpDatabase() throws SQLException {
        testDb = new TestDatabaseManager();
        testDb.initialize();
    }

    @AfterAll
    static void tearDownDatabase() {
        testDb.shutdown();
    }

    @BeforeEach
    void setUp() throws Exception {
        sessionDao = new ChatSessionDao();
        messageDao = new ChatMessageDao();
        injectTestConnection(testDb.getConnection());
        // Clean tables before each test
        testDb.getConnection().createStatement().execute("DELETE FROM Chat_History");
        testDb.getConnection().createStatement().execute("DELETE FROM Chat_Session");
    }

    @Test
    void createMessage_returnsMessageWithGeneratedId() throws SQLException {
        ChatSession session = sessionDao.create("Test Session");
        ChatMessage msg = new ChatMessage(session.getSessionId(), "User", "Hello!", null);

        ChatMessage saved = messageDao.create(msg);

        assertTrue(saved.getMsgId() > 0);
        assertEquals("User", saved.getRole());
        assertEquals("Hello!", saved.getContent());
    }

    @Test
    void findBySessionId_returnsMessagesInChronologicalOrder() throws SQLException {
        ChatSession session = sessionDao.create("Ordered Test");

        messageDao.create(new ChatMessage(session.getSessionId(), "User", "First", null));
        messageDao.create(new ChatMessage(session.getSessionId(), "Assistant", "Second", null));
        messageDao.create(new ChatMessage(session.getSessionId(), "User", "Third", null));

        List<ChatMessage> messages = messageDao.findBySessionId(session.getSessionId());

        assertEquals(3, messages.size());
        assertEquals("First", messages.get(0).getContent());
        assertEquals("Second", messages.get(1).getContent());
        assertEquals("Third", messages.get(2).getContent());
    }

    @Test
    void findBySessionId_emptySession_returnsEmptyList() throws SQLException {
        ChatSession session = sessionDao.create("Empty Session");
        List<ChatMessage> messages = messageDao.findBySessionId(session.getSessionId());
        assertTrue(messages.isEmpty());
    }

    @Test
    void findBySessionId_doesNotReturnMessagesFromOtherSessions() throws SQLException {
        ChatSession s1 = sessionDao.create("Session 1");
        ChatSession s2 = sessionDao.create("Session 2");

        messageDao.create(new ChatMessage(s1.getSessionId(), "User", "In session 1", null));
        messageDao.create(new ChatMessage(s2.getSessionId(), "User", "In session 2", null));

        List<ChatMessage> s1Messages = messageDao.findBySessionId(s1.getSessionId());
        assertEquals(1, s1Messages.size());
        assertEquals("In session 1", s1Messages.get(0).getContent());
    }

    @Test
    void deleteBySessionId_removesAllMessagesForSession() throws SQLException {
        ChatSession session = sessionDao.create("Delete Test");
        messageDao.create(new ChatMessage(session.getSessionId(), "User", "msg1", null));
        messageDao.create(new ChatMessage(session.getSessionId(), "Assistant", "msg2", null));

        messageDao.deleteBySessionId(session.getSessionId());

        List<ChatMessage> messages = messageDao.findBySessionId(session.getSessionId());
        assertTrue(messages.isEmpty());
    }

    @Test
    void cascadeDelete_deletingSessionRemovesMessages() throws SQLException {
        ChatSession session = sessionDao.create("Cascade Test");
        messageDao.create(new ChatMessage(session.getSessionId(), "User", "cascade msg", null));

        sessionDao.delete(session.getSessionId());

        List<ChatMessage> messages = messageDao.findBySessionId(session.getSessionId());
        assertTrue(messages.isEmpty());
    }

    @Test
    void createMessage_preservesRoleConstraint() throws SQLException {
        ChatSession session = sessionDao.create("Role Test");

        ChatMessage userMsg = messageDao.create(
                new ChatMessage(session.getSessionId(), "User", "user says", null));
        ChatMessage assistantMsg = messageDao.create(
                new ChatMessage(session.getSessionId(), "Assistant", "assistant says", null));

        assertEquals("User", userMsg.getRole());
        assertEquals("Assistant", assistantMsg.getRole());
    }

    @Test
    void createMessage_invalidRole_throwsException() throws SQLException {
        ChatSession session = sessionDao.create("Invalid Role");
        ChatMessage msg = new ChatMessage(session.getSessionId(), "InvalidRole", "bad", null);

        assertThrows(SQLException.class, () -> messageDao.create(msg));
    }

    private void injectTestConnection(Connection conn) throws Exception {
        var dbManager = com.mosaic.client.db.DatabaseManager.getInstance();
        Field connectionField = dbManager.getClass().getDeclaredField("connection");
        connectionField.setAccessible(true);
        connectionField.set(dbManager, conn);
    }
}
