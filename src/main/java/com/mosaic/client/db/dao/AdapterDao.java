package com.mosaic.client.db.dao;

import com.mosaic.client.db.DatabaseManager;
import com.mosaic.client.db.model.Adapter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class AdapterDao {

    private Connection getConnection() {
        return DatabaseManager.getInstance().getConnection();
    }

    public void upsert(Adapter adapter) throws SQLException {
        String sql = """
            INSERT INTO Local_Adapters (adapter_id, name, domain, file_path, file_hash, size_mb)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(adapter_id) DO UPDATE SET
                name = excluded.name,
                domain = excluded.domain,
                file_path = excluded.file_path,
                file_hash = excluded.file_hash,
                size_mb = excluded.size_mb
            """;
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
