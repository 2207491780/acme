package com.acme.common.test;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 零 Docker 原生嵌入式 PostgreSQL 17 测试底座（单 JVM 共享实例 + 毫秒级快照重置）。
 *
 * <p>设计目标：彻底消除本地测试对 Docker Daemon 与每类启停容器的依赖，同时保留 100% 真实 PostgreSQL 17
 * 语义（Flyway 全量迁移、pgcrypto、jsonb、部分唯一索引、RLS 策略与非超管角色）。</p>
 *
 * <ul>
 *   <li>同 JVM 内仅启动 1 个原生 postgres 进程（关闭 fsync/synchronous_commit/full_page_writes 加速测试写盘），
 *       JVM 退出时由 ShutdownHook 自动清理数据目录并停止进程；</li>
 *   <li>首次 Flyway 迁移后通过 {@link #captureSnapshotIfAbsent(String, String...)} 将非空种子表与序列状态
 *       快照至 {@code _acme_snap_<schema>}；后续测试类运行前调用 {@link #resetToSnapshot(String, String...)}
 *       以 {@code session_replication_role = 'replica'} + {@code TRUNCATE ... CASCADE} + 快照回灌 + 序列重置，
 *       约 15~40ms 完成库态还原，既保障类间 100% 隔离又不破坏共享 Spring ApplicationContext 的连接池。</li>
 * </ul>
 */
public final class EmbeddedPostgresSupport {

    public static final String DEFAULT_DATABASE = "acme";
    public static final String DEFAULT_USERNAME = "postgres";
    public static final String DEFAULT_PASSWORD = "postgres";

    private static volatile EmbeddedPostgresSupport instance;
    private static final Object INIT_LOCK = new Object();

    private final EmbeddedPostgres postgres;
    private final int port;
    private final Set<String> createdDatabases = ConcurrentHashMap.newKeySet();
    private final Map<String, SchemaSnapshot> snapshots = new ConcurrentHashMap<>();

    private EmbeddedPostgresSupport(EmbeddedPostgres postgres) {
        this.postgres = postgres;
        this.port = postgres.getPort();
        this.createdDatabases.add("postgres");
        ensureDatabase(DEFAULT_DATABASE);
    }

    /** 获取当前 JVM 单例嵌入式 PostgreSQL 17 实例（首次调用自动启动并建库 {@code acme}）。 */
    public static EmbeddedPostgresSupport shared() {
        EmbeddedPostgresSupport current = instance;
        if (current != null) {
            return current;
        }
        synchronized (INIT_LOCK) {
            if (instance == null) {
                instance = startNew();
            }
            return instance;
        }
    }

    private static EmbeddedPostgresSupport startNew() {
        try {
            // 跨 JVM 复用已解压的 PG 17 原生二进制目录（避免每次 Maven 模块启动重复 LZMA 解压 80MB 耗时 ~20s），
            // 同时每个 JVM 使用独立临时数据目录并随退出清理，完全支持多模块并发构建（mvn -T 1C）。
            java.nio.file.Path binCacheDir = java.nio.file.Path.of(
                    System.getProperty("java.io.tmpdir"), "acme-embedded-pg-bin-17.12");
            java.nio.file.Files.createDirectories(binCacheDir);
            java.nio.file.Path dataDir = java.nio.file.Files.createTempDirectory("acme-embedded-pg-data-");

            EmbeddedPostgres pg = EmbeddedPostgres.builder()
                    .setOverrideWorkingDirectory(binCacheDir.toFile())
                    .setDataDirectory(dataDir.toFile())
                    .setCleanDataDirectory(true)
                    .setServerConfig("fsync", "off")
                    .setServerConfig("synchronous_commit", "off")
                    .setServerConfig("full_page_writes", "off")
                    .setServerConfig("max_connections", "200")
                    .setServerConfig("shared_buffers", "128MB")
                    .start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    pg.close();
                } catch (IOException ignored) {
                    // JVM 退出清理忽略关闭异常
                }
            }, "acme-embedded-pg-shutdown"));
            return new EmbeddedPostgresSupport(pg);
        } catch (IOException ex) {
            throw new UncheckedIOException("启动嵌入式 PostgreSQL 17 失败", ex);
        }
    }

    public int port() {
        return port;
    }

    public String username() {
        return DEFAULT_USERNAME;
    }

    public String password() {
        return DEFAULT_PASSWORD;
    }

    public String jdbcUrl() {
        return jdbcUrl(DEFAULT_DATABASE, null);
    }

    public String jdbcUrlWithSchema(String currentSchema) {
        return jdbcUrl(DEFAULT_DATABASE, currentSchema);
    }

    public String jdbcUrl(String database, String currentSchema) {
        ensureDatabase(database);
        String base = "jdbc:postgresql://localhost:" + port + "/" + database;
        if (currentSchema == null || currentSchema.isBlank()) {
            return base;
        }
        return base + "?currentSchema=" + currentSchema;
    }

    /** 确保目标库存在（幂等；供多库隔离场景使用）。 */
    public void ensureDatabase(String database) {
        if (database == null || database.isBlank() || createdDatabases.contains(database)) {
            return;
        }
        synchronized (createdDatabases) {
            if (createdDatabases.contains(database)) {
                return;
            }
            try (Connection conn = openAdminConnection("postgres");
                 PreparedStatement check = conn.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
                check.setString(1, database);
                try (ResultSet rs = check.executeQuery()) {
                    if (!rs.next()) {
                        try (Statement st = conn.createStatement()) {
                            st.execute("CREATE DATABASE " + quoteIdent(database));
                        }
                    }
                }
                createdDatabases.add(database);
            } catch (SQLException ex) {
                throw new IllegalStateException("创建嵌入式 PG 数据库失败: " + database, ex);
            }
        }
    }

    /** 以超管账号打开指定库的直连 JDBC 连接（不受 RLS 约束，供快照/重置/断言真相使用）。 */
    public Connection openAdminConnection(String database) throws SQLException {
        return DriverManager.getConnection(
                "jdbc:postgresql://localhost:" + port + "/" + database,
                DEFAULT_USERNAME,
                DEFAULT_PASSWORD);
    }

    /**
     * 捕获指定 schema 的迁移后种子快照（每个 {@code database + schema} 仅捕获一次）。
     *
     * <p>应在 Flyway 迁移完成、业务测试尚未写脏数据前调用。</p>
     */
    public void captureSnapshotIfAbsent(String database, String... schemas) {
        for (String schema : normalizeSchemas(schemas)) {
            String key = snapshotKey(database, schema);
            if (!snapshots.containsKey(key)) {
                synchronized (snapshots) {
                    if (!snapshots.containsKey(key)) {
                        snapshots.put(key, doCaptureSnapshot(database, schema));
                    }
                }
            }
        }
    }

    /**
     * 将指定 schema 毫秒级重置回迁移后种子快照状态（若尚未捕获快照，则先捕获后直接返回）。
     *
     * <p>使用超管连接 + {@code SET LOCAL session_replication_role = 'replica'} 绕过 RLS 与外键触发器，
     * 不关闭/重建 HikariCP 连接池，与共享 Spring {@code ApplicationContext} 完全兼容。</p>
     */
    public void resetToSnapshot(String database, String... schemas) {
        List<String> targetSchemas = normalizeSchemas(schemas);
        for (String schema : targetSchemas) {
            String key = snapshotKey(database, schema);
            SchemaSnapshot snap = snapshots.get(key);
            if (snap == null) {
                synchronized (snapshots) {
                    snap = snapshots.get(key);
                    if (snap == null) {
                        snapshots.put(key, doCaptureSnapshot(database, schema));
                        continue;
                    }
                }
            }
            doRestoreSnapshot(database, snap);
        }
    }

    private SchemaSnapshot doCaptureSnapshot(String database, String schema) {
        try (Connection conn = openAdminConnection(database)) {
            conn.setAutoCommit(false);
            CopyManager copyManager = conn.unwrap(PGConnection.class).getCopyAPI();
            List<String> tables = listUserTables(conn, schema);
            List<SeededTable> seededTables = new ArrayList<>();
            for (String table : tables) {
                if (hasAnyRow(conn, schema, table)) {
                    List<String> insertCols = listInsertableColumns(conn, schema, table);
                    String cols = insertCols.stream()
                            .map(EmbeddedPostgresSupport::quoteIdent)
                            .collect(Collectors.joining(", "));
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    copyManager.copyOut(
                            "COPY " + quoteIdent(schema) + "." + quoteIdent(table)
                                    + " (" + cols + ") TO STDOUT (FORMAT BINARY)",
                            buffer);
                    seededTables.add(new SeededTable(table, cols, buffer.toByteArray()));
                }
            }
            List<SequenceState> sequences = captureSequences(conn, schema);
            conn.commit();
            return new SchemaSnapshot(schema, seededTables, sequences);
        } catch (SQLException | IOException ex) {
            throw new IllegalStateException("捕获嵌入式 PG 快照失败: " + database + "." + schema, ex);
        }
    }

    private void doRestoreSnapshot(String database, SchemaSnapshot snap) {
        try (Connection conn = openAdminConnection(database)) {
            conn.setAutoCommit(false);
            // 每次还原实时枚举当前 schema 业务表，连带清理测试期通过 CREATE TABLE IF NOT EXISTS 自举的临时表
            List<String> currentTables = listUserTables(conn, snap.schema);
            if (currentTables.isEmpty()) {
                return;
            }
            try (Statement st = conn.createStatement()) {
                st.execute("SET LOCAL session_replication_role = 'replica'");
                String joinedTables = currentTables.stream()
                        .map(t -> quoteIdent(snap.schema) + "." + quoteIdent(t))
                        .collect(Collectors.joining(", "));
                st.execute("TRUNCATE TABLE " + joinedTables + " RESTART IDENTITY CASCADE");
            }

            CopyManager copyManager = conn.unwrap(PGConnection.class).getCopyAPI();
            for (SeededTable seeded : snap.seededTables) {
                copyManager.copyIn(
                        "COPY " + quoteIdent(snap.schema) + "." + quoteIdent(seeded.tableName)
                                + " (" + seeded.quotedColumns + ") FROM STDIN (FORMAT BINARY)",
                        new ByteArrayInputStream(seeded.binaryData));
            }

            for (SequenceState seq : snap.sequences) {
                long val = seq.lastValue != null ? seq.lastValue : seq.startValue;
                boolean called = seq.lastValue != null;
                try (PreparedStatement ps = conn.prepareStatement("SELECT setval(?, ?, ?)")) {
                    ps.setString(1, quoteIdent(snap.schema) + "." + quoteIdent(seq.name));
                    ps.setLong(2, val);
                    ps.setBoolean(3, called);
                    ps.execute();
                }
            }
            conn.commit();
        } catch (SQLException | IOException ex) {
            throw new IllegalStateException("还原嵌入式 PG 快照失败: " + database + "." + snap.schema, ex);
        }
    }

    private static List<String> listUserTables(Connection conn, String schema) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT tablename FROM pg_tables
                WHERE schemaname = ? AND tablename <> 'flyway_schema_history'
                ORDER BY tablename
                """)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
        }
        return tables;
    }

    private static boolean hasAnyRow(Connection conn, String schema, String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT 1 FROM " + quoteIdent(schema) + "." + quoteIdent(table) + " LIMIT 1")) {
            return rs.next();
        }
    }

    private static List<String> listInsertableColumns(Connection conn, String schema, String table)
            throws SQLException {
        List<String> cols = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = ? AND table_name = ? AND is_generated = 'NEVER'
                ORDER BY ordinal_position
                """)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(rs.getString(1));
                }
            }
        }
        return cols;
    }

    private static List<SequenceState> captureSequences(Connection conn, String schema) throws SQLException {
        List<SequenceState> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT sequencename, start_value, last_value
                FROM pg_sequences
                WHERE schemaname = ?
                ORDER BY sequencename
                """)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    long startVal = rs.getLong(2);
                    Object lastObj = rs.getObject(3);
                    Long lastVal = lastObj == null ? null : ((Number) lastObj).longValue();
                    result.add(new SequenceState(name, startVal, lastVal));
                }
            }
        }
        return result;
    }

    private static List<String> normalizeSchemas(String... schemas) {
        if (schemas == null || schemas.length == 0) {
            return List.of("public");
        }
        return Arrays.stream(schemas)
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static String snapshotKey(String database, String schema) {
        return database + "::" + schema;
    }

    private static String quoteIdent(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    private record SeededTable(String tableName, String quotedColumns, byte[] binaryData) {
    }

    private record SequenceState(String name, long startValue, Long lastValue) {
    }

    private record SchemaSnapshot(
            String schema,
            List<SeededTable> seededTables,
            List<SequenceState> sequences) {
    }
}
