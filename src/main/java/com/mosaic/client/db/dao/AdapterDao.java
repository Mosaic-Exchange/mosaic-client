package com.mosaic.client.db.dao;

import com.mosaic.client.db.DatabaseManager;
import com.mosaic.client.db.model.Adapter;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Data access object for the Local_Adapters table.
 */
public class AdapterDao {

    private Connection getConnection() {
        return DatabaseManager.getInstance().getConnection();
    }

    /**
     * Inserts or replaces an adapter record.
     */
    public void upsert(Adapter adapter) throws SQLException {
        String sql = "INSERT OR REPLACE INTO Local_Adapters "
                   + "(adapter_id, name, domain, file_path, file_hash, size_mb) "
                   + "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, adapter.getAdapterId());
            ps.setString(2, adapter.getName());
            ps.setString(3, adapter.getDomain());
            ps.setString(4, adapter.getFilePath());
            ps.setString(5, adapter.getFileHash());
            ps.setInt(6, adapter.getSizeMb());
            ps.executeUpdate();
        }
    }

    /**
     * Retrieves all adapters, sorted alphabetically by name.
     */
    public List<Adapter> findAll() throws SQLException {
        String sql = "SELECT adapter_id, name, domain, file_path, file_hash, size_mb "
                   + "FROM Local_Adapters ORDER BY name ASC";
        List<Adapter> list = new ArrayList<>();
        try (Statement stmt = getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
    }

    /**
     * Finds adapters matching a specific domain.
     */
    public List<Adapter> findByDomain(String domain) throws SQLException {
        String sql = "SELECT adapter_id, name, domain, file_path, file_hash, size_mb "
                   + "FROM Local_Adapters WHERE domain = ? ORDER BY name ASC";
        List<Adapter> list = new ArrayList<>();
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, domain);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    /**
     * Finds a single adapter by its ID, or null if not found.
     */
    public Adapter findById(String adapterId) throws SQLException {
        String sql = "SELECT adapter_id, name, domain, file_path, file_hash, size_mb "
                   + "FROM Local_Adapters WHERE adapter_id = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, adapterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        }
        return null;
    }

    /**
     * Deletes an adapter by its ID.
     */
    public void delete(String adapterId) throws SQLException {
        String sql = "DELETE FROM Local_Adapters WHERE adapter_id = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, adapterId);
            ps.executeUpdate();
        }
    }

    private Adapter mapRow(ResultSet rs) throws SQLException {
        return new Adapter(
            rs.getString("adapter_id"),
            rs.getString("name"),
            rs.getString("domain"),
            rs.getString("file_path"),
            rs.getString("file_hash"),
            rs.getInt("size_mb")
        );
    }
}
