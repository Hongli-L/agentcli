package com.agentcli.store;

import com.agentcli.common.AppConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite 嵌入式持久化底座（内嵌用法借鉴参考实现：{@code DriverManager.getConnection("jdbc:sqlite:...")}）。
 *
 * <p>单一进程内数据库文件，无独立服务进程；承载长期记忆、Plan 任务状态与审计日志三类落盘数据。
 * 本类不长期持有连接——每次操作通过 {@link #connect()} 打开一条短生命周期连接，调用方负责关闭，
 * 由 {@code busy_timeout} 吸收并发写冲突。轻量「迁移」由 {@link #ensureSchema()} 的
 * {@code CREATE TABLE IF NOT EXISTS} 幂等建表承担。
 */
public class SqliteStore {

    private static final String DB_FILE_NAME = "agentcli.db";
    private static SqliteStore DEFAULT;

    private final Path dbPath;
    private final String url;

    private SqliteStore(Path dbPath, String url) {
        this.dbPath = dbPath;
        this.url = url;
    }

    /** 进程级默认库：{@code ~/.agentcli/db/agentcli.db}（懒加载单例，避免重复解析路径）。 */
    public static synchronized SqliteStore defaultStore() throws SQLException {
        if (DEFAULT == null) {
            DEFAULT = open(defaultDbPath());
        }
        return DEFAULT;
    }

    public static Path defaultDbPath() {
        return AppConfig.CONFIG_DIR.resolve("db").resolve(DB_FILE_NAME);
    }

    /** 打开（必要时创建）指定路径的库，自动建表。 */
    public static SqliteStore open(Path dbPath) throws SQLException {
        if (dbPath.getParent() != null) {
            try {
                Files.createDirectories(dbPath.getParent());
            } catch (IOException e) {
                throw new SQLException("无法创建数据库目录: " + dbPath.getParent(), e);
            }
        }
        String url = "jdbc:sqlite:" + dbPath + "?busy_timeout=5000";
        SqliteStore store = new SqliteStore(dbPath, url);
        store.ensureSchema();
        return store;
    }

    /** 打开一条（短生命周期）连接；调用方负责在用完后关闭。 */
    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url);
    }

    public Path dbPath() {
        return dbPath;
    }

    private void ensureSchema() throws SQLException {
        try (Connection conn = connect();
             Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS memory_entries (
                    id TEXT PRIMARY KEY,
                    content TEXT NOT NULL,
                    type TEXT NOT NULL,
                    metadata_json TEXT,
                    token_count INTEGER NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS plans (
                    plan_id TEXT PRIMARY KEY,
                    goal TEXT NOT NULL,
                    status TEXT NOT NULL,
                    summary TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS plan_tasks (
                    plan_id TEXT NOT NULL,
                    task_id TEXT NOT NULL,
                    description TEXT NOT NULL,
                    type TEXT NOT NULL,
                    status TEXT NOT NULL,
                    dependencies TEXT,
                    result_summary TEXT,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY (plan_id, task_id)
                )
                """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS audit_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    target TEXT,
                    detail_json TEXT,
                    tokens INTEGER,
                    latency_ms INTEGER
                )
                """);
        }
    }
}
