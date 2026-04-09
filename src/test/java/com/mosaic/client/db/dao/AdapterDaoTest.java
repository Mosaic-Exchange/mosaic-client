package com.mosaic.client.db.dao;

import com.mosaic.client.db.TestDatabaseManager;
import com.mosaic.client.db.model.Adapter;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AdapterDao using an in-memory SQLite database.
 */
class AdapterDaoTest {

    private static TestDatabaseManager testDb;
    private AdapterDao dao;

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
        dao = new AdapterDao();
        injectTestConnection(testDb.getConnection());
        testDb.getConnection().createStatement().execute("DELETE FROM Chat_History");
        testDb.getConnection().createStatement().execute("DELETE FROM Local_Adapters");
    }

    @Test
    void upsert_insertsNewAdapter() throws SQLException {
        Adapter adapter = new Adapter("uuid-1", "Gardening Expert", "Gardening",
                "/adapters/gardening.gguf", "abc123hash", 300);
        dao.upsert(adapter);

        Adapter found = dao.findById("uuid-1");
        assertNotNull(found);
        assertEquals("Gardening Expert", found.getName());
        assertEquals("Gardening", found.getDomain());
        assertEquals("/adapters/gardening.gguf", found.getFilePath());
        assertEquals("abc123hash", found.getFileHash());
        assertEquals(300, found.getSizeMb());
    }

    @Test
    void upsert_updatesExistingAdapter() throws SQLException {
        dao.upsert(new Adapter("uuid-2", "Old Name", "Physics",
                "/old/path.gguf", "oldhash", 100));

        dao.upsert(new Adapter("uuid-2", "New Name", "Chemistry",
                "/new/path.gguf", "newhash", 200));

        Adapter found = dao.findById("uuid-2");
        assertNotNull(found);
        assertEquals("New Name", found.getName());
        assertEquals("Chemistry", found.getDomain());
        assertEquals(200, found.getSizeMb());
    }

    @Test
    void findAll_returnsSortedByName() throws SQLException {
        dao.upsert(new Adapter("z-id", "Zebra Expert", "Animals", "", "", 10));
        dao.upsert(new Adapter("a-id", "Alpha Expert", "Math", "", "", 20));
        dao.upsert(new Adapter("m-id", "Middle Expert", "Science", "", "", 15));

        List<Adapter> all = dao.findAll();
        assertEquals(3, all.size());
        assertEquals("Alpha Expert", all.get(0).getName());
        assertEquals("Middle Expert", all.get(1).getName());
        assertEquals("Zebra Expert", all.get(2).getName());
    }

    @Test
    void findByDomain_filtersCorrectly() throws SQLException {
        dao.upsert(new Adapter("id-1", "Expert A", "Physics", "", "", 10));
        dao.upsert(new Adapter("id-2", "Expert B", "Physics", "", "", 20));
        dao.upsert(new Adapter("id-3", "Expert C", "Cooking", "", "", 30));

        List<Adapter> physics = dao.findByDomain("Physics");
        assertEquals(2, physics.size());

        List<Adapter> cooking = dao.findByDomain("Cooking");
        assertEquals(1, cooking.size());
        assertEquals("Expert C", cooking.get(0).getName());
    }

    @Test
    void findByDomain_noMatch_returnsEmpty() throws SQLException {
        dao.upsert(new Adapter("id-1", "Expert", "Physics", "", "", 10));
        List<Adapter> result = dao.findByDomain("NonExistent");
        assertTrue(result.isEmpty());
    }

    @Test
    void findById_nonExistent_returnsNull() throws SQLException {
        assertNull(dao.findById("non-existent-uuid"));
    }

    @Test
    void delete_removesAdapter() throws SQLException {
        dao.upsert(new Adapter("del-id", "To Delete", "Testing", "", "", 5));
        dao.delete("del-id");
        assertNull(dao.findById("del-id"));
    }

    @Test
    void delete_nonExistent_doesNotThrow() {
        assertDoesNotThrow(() -> dao.delete("non-existent"));
    }

    private void injectTestConnection(Connection conn) throws Exception {
        var dbManager = com.mosaic.client.db.DatabaseManager.getInstance();
        Field connectionField = dbManager.getClass().getDeclaredField("connection");
        connectionField.setAccessible(true);
        connectionField.set(dbManager, conn);
    }
}
