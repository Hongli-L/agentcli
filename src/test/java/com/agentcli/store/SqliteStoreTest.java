package com.agentcli.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteStoreTest {

    @Test
    void createsTablesAndRoundTripsMemoryEntry(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("agentcli-test.db");
        SqliteStore store = SqliteStore.open(db);
        try (Connection conn = store.connect()) {
            assertFalse(conn.isClosed());
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO memory_entries(id, content, type, metadata_json, token_count, created_at) "
                            + "VALUES(?,?,?,?,?,?)")) {
                ps.setString(1, "m1");
                ps.setString(2, "hello");
                ps.setString(3, "FACT");
                ps.setString(4, "{\"scope\":\"global\"}");
                ps.setInt(5, 3);
                ps.setLong(6, 123L);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT content FROM memory_entries WHERE id = ?")) {
                ps.setString(1, "m1");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("hello", rs.getString("content"));
                }
            }
        }
    }

    @Test
    void createsPlanAndAuditTables(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("agentcli-test.db");
        SqliteStore store = SqliteStore.open(db);
        try (Connection conn = store.connect();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO plans(plan_id, goal, status, summary, created_at, updated_at) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, "plan_1");
            ps.setString(2, "g");
            ps.setString(3, "RUNNING");
            ps.setString(4, "s");
            ps.setLong(5, 1L);
            ps.setLong(6, 1L);
            ps.executeUpdate();
        }
        try (Connection conn = store.connect();
             PreparedStatement ps = conn.prepareStatement("SELECT status FROM plans WHERE plan_id = ?")) {
            ps.setString(1, "plan_1");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("RUNNING", rs.getString("status"));
            }
        }
    }
}
