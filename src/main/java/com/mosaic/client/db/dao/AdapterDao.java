package com.mosaic.client.db.dao;

import com.mosaic.client.db.DatabaseManager;
import com.mosaic.client.ui.screens.expert.Expert;

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

    public void upsert(Expert adapter) throws SQLException {
        String sql = "INSERT INTO Local_Adapters (file_path, name, domain, server_side_id) " +
                     "VALUES (?, ?, ?, ?) " +
                     "ON CONFLICT(file_path) DO UPDATE SET " +
                     "name = ?, " +
                     "domain = ?, " +
                     "server_side_id = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, adapter.getAdapterFile());
            ps.setString(2, adapter.getName());
            ps.setString(3, adapter.getDomain());
            ps.setString(4, adapter.getServerSideId());
            ps.setString(5, adapter.getName());
            ps.setString(6, adapter.getDomain());
            ps.setString(7, adapter.getServerSideId());
            ps.executeUpdate();
        }
    }

    public List<Expert> findAll() throws SQLException {
        String sql = "SELECT name, domain, file_path, server_side_id "
                   + "FROM Local_Adapters ORDER BY name ASC";
        List<Expert> list = new ArrayList<>();
        try (Statement stmt = getConnection().createStatement();
            ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
    }

    private Expert mapRow(ResultSet rs) throws SQLException {
        return new Expert(
            rs.getString("name"),
            rs.getString("domain"),
            Expert.Source.LOCAL,
            rs.getString("file_path"),
            rs.getString("server_side_id")
        );
    }
}
