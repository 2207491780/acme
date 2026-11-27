package com.acme.platform.system;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261125-230807（V35 迁移幂等/正确性，支撑 AC-17 字典树形存储面）字典树形迁移集成测试
 * （Testcontainers PG，装配口径照抄 {@code SystemIntegrationTest}/
 * {@code REQ20261119051119ButtonMenuMigrationTest}；无 Docker 自动跳过——判定口径：
 * Tests run: N, Skipped: 0）。
 *
 * <p>契约（API 节「字典树形（V35 迁移）」+ 确认记录④ + 变更清单）：{@code sys_dict_type} +
 * {@code dict_kind varchar(16) NOT NULL DEFAULT 'FLAT'}（FLAT|TREE）；{@code sys_dict_item} +
 * {@code parent_id bigint NULL}；既有数据迁移后全为 FLAT 默认值（平铺行为零变化的迁移锚）。</p>
 *
 * <p>口径说明（契约歧义/并行实现假设就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-6 V35 文件名按 {@code classpath*:db/migration/system/V35__*.sql} 通配解析
 *       （SystemIntegrationTest V26/V27/V32 先例口径）。</li>
 *   <li>AMB-7 幂等重放=迁移语句全量重放（契约钉死 V35 带「幂等守卫」，ADD COLUMN IF NOT EXISTS /
 *       守卫语句应可重放）；假设语句不含分号字面量与 dollar-quote 块，违者红灯交 TL 裁决。</li>
 *   <li>列形状四要素按契约钉死：dict_kind varchar(16)/NOT NULL/DEFAULT 'FLAT'、parent_id bigint/NULL。</li>
 * </ul>
 *
 * <p>预期红（实现未落地，钉死事实⑨）：V35 文件缺失/列缺失 → 断言失败=实现缺口锚点；
 * 装配失败才属用例缺陷需自修。</p>
 */
class REQ20261125230807DictTreeV35MigrationIntegrationTest extends AbstractSystemIntegrationTest {

    private static final long DICT_ID_BASE = 92308060000L;
    private static final long DICT_ID_TOP = 92308069999L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ---------- V35 迁移就绪：列形状按契约钉死 ----------

    @Test
    void should_add_dict_kind_and_parent_id_columns_when_v35_applied() {
        assertV35ResourcesPresent();

        Map<String, Object> kind = jdbcTemplate.queryForMap("""
                select udt_name, character_maximum_length, is_nullable, coalesce(column_default, '') as column_default
                from information_schema.columns
                where table_schema = 'public' and table_name = 'sys_dict_type' and column_name = 'dict_kind'
                """);
        assertThat(String.valueOf(kind.get("udt_name")))
                .as("dict_kind 应为 varchar（契约钉死 varchar(16)）").isEqualTo("varchar");
        Object kindLength = kind.get("character_maximum_length");
        assertThat(kindLength == null ? null : ((Number) kindLength).intValue())
                .as("dict_kind 长度=16（契约钉死 varchar(16)）").isEqualTo(16);
        assertThat(kind.get("is_nullable")).as("dict_kind NOT NULL（契约钉死）").isEqualTo("NO");
        // PG 目录对字符串缺省的规范渲染带引号与 cast（'FLAT'::character varying）；归一（剥 cast 尾缀与
        // 引号）后断言裸值——缺省缺失（coalesce ''）或值不符仍失败，不掩盖 null / 缺失缺省
        String kindDefaultNormalized = String.valueOf(kind.get("column_default"))
                .replaceAll("::.*$", "")
                .replace("'", "");
        assertThat(kindDefaultNormalized)
                .as("dict_kind 缺省 FLAT（契约钉死 DEFAULT 'FLAT'；PG 规范渲染归一后断言）")
                .isEqualTo("FLAT");

        Map<String, Object> parentId = jdbcTemplate.queryForMap("""
                select udt_name, is_nullable
                from information_schema.columns
                where table_schema = 'public' and table_name = 'sys_dict_item' and column_name = 'parent_id'
                """);
        assertThat(String.valueOf(parentId.get("udt_name")))
                .as("parent_id 应为 bigint（契约钉死 bigint NULL）").isEqualTo("int8");
        assertThat(parentId.get("is_nullable")).as("parent_id 可空（契约钉死 NULL）").isEqualTo("YES");

        // 既有数据迁移后全为 FLAT 默认值（平铺零变化的迁移锚；种子无字典行时计数为 0 同样成立）
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_dict_type where dict_kind is distinct from 'FLAT'", Long.class))
                .as("V35 后既有 dict_type 行应全为 FLAT 缺省").isZero();
    }

    // ---------- V35 幂等：全量语句重放两次，状态零变化 ----------

    @Test
    void should_keep_dict_state_stable_when_v35_statements_replayed() throws IOException {
        assertV35ResourcesPresent();

        long typeCountBefore = jdbcTemplate.queryForObject("select count(*) from sys_dict_type", Long.class);
        long itemCountBefore = jdbcTemplate.queryForObject("select count(*) from sys_dict_item", Long.class);
        long flatCountBefore = jdbcTemplate.queryForObject(
                "select count(*) from sys_dict_type where dict_kind = 'FLAT'", Long.class);
        long nullParentBefore = jdbcTemplate.queryForObject(
                "select count(*) from sys_dict_item where parent_id is null", Long.class);

        replayV35Statements();
        replayV35Statements();

        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_dict_type", Long.class))
                .as("重放两次后 dict_type 行数零变化").isEqualTo(typeCountBefore);
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_dict_item", Long.class))
                .as("重放两次后 dict_item 行数零变化").isEqualTo(itemCountBefore);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_dict_type where dict_kind = 'FLAT'", Long.class))
                .as("重放两次后 FLAT 计数零变化（幂等守卫不重置缺省值）").isEqualTo(flatCountBefore);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_dict_item where parent_id is null", Long.class))
                .as("重放两次后 parent_id 空值计数零变化").isEqualTo(nullParentBefore);
    }

    // ---------- 支撑 ----------

    private void assertV35ResourcesPresent() {
        try {
            var resources = new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                    .getResources("classpath*:db/migration/system/V35__*.sql");
            assertThat(resources)
                    .as("V35 迁移脚本缺失（backend 未落盘或命名不符 V35__*.sql）——预期红锚点")
                    .isNotEmpty();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void replayV35Statements() throws IOException {
        var resources = new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/system/V35__*.sql");
        assertThat(resources).as("V35 迁移脚本缺失").isNotEmpty();
        for (var resource : resources) {
            String script = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            for (String chunk : script.split(";")) {
                String statement = chunk.lines()
                        .filter(line -> !line.strip().startsWith("--"))
                        .collect(java.util.stream.Collectors.joining("\n"))
                        .strip();
                if (!statement.isEmpty()) {
                    jdbcTemplate.execute(statement);
                }
            }
        }
    }
}
