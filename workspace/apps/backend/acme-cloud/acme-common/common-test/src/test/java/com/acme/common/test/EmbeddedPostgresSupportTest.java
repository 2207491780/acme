package com.acme.common.test;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 零 Docker 原生嵌入式 PostgreSQL 17 底座回归测试：
 * 校验 PG 17 真实能力（pgcrypto / jsonb / 部分唯一索引 / RLS）与毫秒级快照捕获/还原语义。
 */
class EmbeddedPostgresSupportTest {

    @Test
    void should_supportPg17FeaturesAndResetSchemaToSnapshot_when_mutated() throws Exception {
        EmbeddedPostgresSupport pg = EmbeddedPostgresSupport.shared();
        String testDb = "acme_snap_test";
        pg.ensureDatabase(testDb);

        try (Connection conn = pg.openAdminConnection(testDb);
             Statement st = conn.createStatement()) {
            st.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS snap_demo (
                        id BIGINT PRIMARY KEY,
                        tenant_id VARCHAR(64) NOT NULL,
                        payload JSONB NOT NULL,
                        pwd_hash TEXT NOT NULL,
                        deleted SMALLINT NOT NULL DEFAULT 0
                    )
                    """);
            st.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS uk_snap_demo_active
                    ON snap_demo (tenant_id) WHERE deleted = 0
                    """);
            st.execute("ALTER TABLE snap_demo ENABLE ROW LEVEL SECURITY");
            st.execute("""
                    INSERT INTO snap_demo (id, tenant_id, payload, pwd_hash)
                    VALUES (1, 'default', '{"seed":true}'::jsonb, crypt('Admin@123456', gen_salt('bf', 4)))
                    ON CONFLICT (id) DO NOTHING
                    """);
        }

        // 首次捕获快照
        pg.captureSnapshotIfAbsent(testDb, "public");

        // 写脏数据并删种子行
        try (Connection conn = pg.openAdminConnection(testDb);
             Statement st = conn.createStatement()) {
            st.execute("DELETE FROM snap_demo WHERE id = 1");
            st.execute("""
                    INSERT INTO snap_demo (id, tenant_id, payload, pwd_hash)
                    VALUES (2, 'dirty', '{"dirty":true}'::jsonb, 'x')
                    """);
        }

        // 毫秒级还原至快照
        long startNs = System.nanoTime();
        pg.resetToSnapshot(testDb, "public");
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

        assertThat(elapsedMs).as("快照重置应在 2s 内完成（典型 15~50ms）").isLessThan(2_000L);
        try (Connection conn = pg.openAdminConnection(testDb);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, tenant_id, pwd_hash FROM snap_demo ORDER BY id")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong("id")).isEqualTo(1L);
            assertThat(rs.getString("tenant_id")).isEqualTo("default");
            assertThat(rs.getString("pwd_hash")).startsWith("$2");
            assertThat(rs.next()).isFalse();
        }
    }
}
