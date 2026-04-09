package com.mosaic.client.db.dao;

import com.mosaic.client.db.TestDatabaseManager;
import com.mosaic.client.db.model.ChatSession;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ChatSessionDao using an in-memory SQLite database.
 */
class ChatSessionDaoTest {

    private static TestDatabaseManager testDb;
    private ChatSessionDao dao;

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
        dao = new ChatSessionDao();
        // Inject the test connection into the singleton DatabaseManager
        injectTestConnection(testDb.getConnection());
        // Clean the table before each test
        testDb.getConnection().createStatement().execute("DELETE FROM Chat_History");
        testDb.getConnection().createStatement().execute("DELETE FROM Chat_Session");
    }

    @Test
    void createSession_returnsSessionWithGeneratedId() throws SQLException {
        ChatSession session = dao.create("Test Topic");

        assertNotNull(session);
        assertTrue(session.getSessionId() > 0);
        assertEquals("Test Topic", session.getTopic());
    }

    @Test
    void findAll_returnsSessionsNewestFirst() throws SQLException {
        dao.create("First");
        dao.create("Second");
        dao.create("Third");

        List<ChatSession> sessions = dao.findAll();

        assertEquals(3, sessions.size());
        assertEquals("Third", sessions.get(0).getTopic());
        assertEquals("Second", sessions.get(1).getTopic());
        assertEquals("First", sessions.get(2).getTopic());
    }

    @Test
    void findAll_emptyTable_returnsEmptyList() throws SQLException {
        List<ChatSession> sessions = dao.findAll();
        assertTrue(sessions.isEmpty());
    }

    @Test
    void findById_existingSession_returnsSession() throws SQLException {
        ChatSession created = dao.create("Lookup Test");
        ChatSession found = dao.findById(created.getSessionId());

        assertNotNull(found);
        assertEquals(created.getSessionId(), found.getSessionId());
        assertEquals("Lookup Test", found.getTopic());
    }

    @Test
    void findById_nonExistentId_returnsNull() throws SQLException {
        assertNull(dao.findById(99999));
    }

    @Test
    void updateTopic_changesTopic() throws SQLException {
        ChatSession session = dao.create("Old Topic");
        dao.updateTopic(session.getSessionId(), "New Topic");

        ChatSession updated = dao.findById(session.getSessionId());
        assertNotNull(updated);
        assertEquals("New Topic", updated.getTopic());
    }

    @Test
    void delete_removesSession() throws SQLException {
        ChatSession session = dao.create("To Delete");
        dao.delete(session.getSessionId());

        assertNull(dao.findById(session.getSessionId()));
    }

    @Test
    void delete_nonExistentId_doesNotThrow() {
        assertDoesNotThrow(() -> dao.delete(99999));
    }

    /**
     * Inject the in-memory test connection into the singleton DatabaseManager
     * so the DAOs use it instead of the real file-based database.
     */
    private void injectTestConnection(Connection conn) throws Exception {
        // Get the singleton instance
        var dbManager = com.mosaic.client.db.DatabaseManager.getInstance();
        // Use reflection to set the connection field
        Field connectionField = dbManager.getClass().getDeclaredField("connection");
        connectionField.setAccessible(true);
        connectionField.set(dbManager, conn);
    }
}
