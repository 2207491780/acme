package com.acme.platform.system;

import com.acme.common.context.Channel;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REQ-20261125-230807（AC-17）字典树形语义测试（V35 后端可测面，Testcontainers PG，装配口径照抄
 * {@code REQ20261122233426TenantInitTemplateTesterTest}；无 Docker 自动跳过——判定口径：
 * Tests run: N, Skipped: 0）。V35 迁移面（列形状/幂等）另见
 * {@code REQ20261125230807DictTreeV35MigrationIntegrationTest}。
 *
 * <p>契约（API 节「字典树形（V35 迁移）」+ 确认记录④ + 钉死事实③）：TREE 条目读返回嵌套 children
 * 恒数组（叶子 []）；写入校验父条目必属同字典类型/存在/非自身与子孙（防环），违规报 PARAM_INVALID 族
 * 既有码；FLAT 类型提交 parentId → PARAM_INVALID；既有平铺行为零变化（回归锚点）。</p>
 *
 * <p>口径说明（契约歧义/并行实现假设就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-10 字典条目 create/list/update wire path 未在本契约 API 节钉死（契约只钉 DELETE 两处）：
 *       按 DictController 前缀 /admin/v1/system（钉死事实②）+ 条目 CRUD 以类型为宿主的 DELETE 对称形态
 *       推导：POST/GET /admin/v1/system/dict-types/{typeId}/items、PUT /admin/v1/system/dict-items/{itemId}；
 *       404 时按实现披露调整（用例缺陷自查项）。</li>
 *   <li>AMB-11 条目 wire 字段按 record 组件名先例：itemLabel/itemValue/sortOrder；V35 新增 parentId。
 *       字典类型夹具不经 wire 构造（走 JdbcTemplate 直插，V1 列面数据面申报），避免类型创建字段名
 *       歧义；TREE/FLAT 类型分别直插 dict_kind='TREE' / 缺省（V35 前缺省列亦可插）。</li>
 *   <li>AMB-12 条目 update 的乐观锁 version：从同类型条目读侧披露读取后随体回传；读侧未披露 version
 *       时省略（若因此 STALE_WRITE 红灯交 TL 裁决）。</li>
 *   <li>AMB-13 PARAM_INVALID 族=业务拒绝统一口径：断言 Result.code≠0（HTTP 200 业务信封），不钉
 *       字面量（历轮 tester 口径；PARAM_INVALID 具体码字面量由实现既有枚举承载）。</li>
 * </ul>
 *
 * <p>预期红（实现未落地，钉死事实⑨）：TREE 夹具直插依赖 dict_kind 列（缺失→SQL 异常，整组红）；
 * parentId 字段未支持/children 未披露 → 断言失败=实现缺口锚点；FLAT 平铺回归锚（不带 parentId 的
 * create/list）现状即应通过。</p>
 */
class REQ20261125230807DictTreeSemanticsIntegrationTest extends AbstractSystemIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String DICT_TYPES_URL = "/admin/v1/system/dict-types";
    private static final String DICT_ITEMS_URL = "/admin/v1/system/dict-items";

    private static final String FIXTURE_PREFIX = "req230807";

    private static final long DICT_ID_BASE = 92308050000L;
    private static final long DICT_ID_TOP = 92308059999L;

    private static final String INSERT_DICT_TYPE_FLAT = """
            insert into sys_dict_type (id, tenant_id, dict_code, dict_name, status, remark,
                                       created_at, created_by, updated_at, updated_by, version, deleted)
            values (?, 'default', ?, ?, 'ENABLED', null, now(), 'req230807-it', now(), 'req230807-it', 0, false)
            """;

    private static final String INSERT_DICT_TYPE_TREE = """
            insert into sys_dict_type (id, tenant_id, dict_code, dict_name, status, remark, dict_kind,
                                       created_at, created_by, updated_at, updated_by, version, deleted)
            values (?, 'default', ?, ?, 'ENABLED', null, 'TREE', now(), 'req230807-it', now(), 'req230807-it', 0, false)
            """;

    private static final String INSERT_DICT_ITEM = """
            insert into sys_dict_item (id, tenant_id, dict_type_id, item_label, item_value, sort_order, status,
                                       created_at, created_by, updated_at, updated_by, version, deleted)
            values (?, 'default', ?, ?, ?, 0, 'ENABLED', now(), 'req230807-it', now(), 'req230807-it', 0, false)
            """;

    @Autowired
    private WebApplicationContext webContext;

    @Autowired
    private InternalTokenVerifier tokenVerifier;

    @Autowired
    private InternalTokenIssuer tokenIssuer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mvc;

    private static int seq;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        // seq 不按用例重置：类型夹具 id 与 itemValue 由 seq 派生，须跨用例唯一——wire 创建条目按
        // dict_type_id 区间清理（见 cleanUp），若类型 id / 值跨用例复用会命中 (type, value) 唯一约束
    }

    @AfterEach
    void cleanUp() {
        // 共享环境脏数据 teardown：wire 创建条目 id 后端生成（不在夹具 ID 区间），必须按
        // dict_type_id 区间清理（同时覆盖直插夹具条目），再按 ID 区间清类型，并清上下文线程残留
        jdbcTemplate.update("delete from sys_dict_item where dict_type_id between ? and ?",
                DICT_ID_BASE, DICT_ID_TOP);
        jdbcTemplate.update("delete from sys_dict_type where id between ? and ?", DICT_ID_BASE, DICT_ID_TOP);
        com.acme.common.context.TenantContext.clear();
        com.acme.common.context.UserContext.clear();
    }

    // ---------- AC-17：TREE 条目读=嵌套 children 恒数组 ----------

    @Test
    void should_nest_child_under_parent_with_always_array_children_when_tree_type_read() throws Exception {
        long treeTypeId = insertTreeType();
        String rootItemValue = rootValue();
        String childItemValue = childValue();
        long rootId = createItemOk(treeTypeId, "根条目", rootItemValue, null);
        long childId = createItemOk(treeTypeId, "子条目", childItemValue, rootId);

        JsonNode items = getItems(treeTypeId);
        JsonNode root = findByValue(items, rootItemValue);
        assertThat(root).as("TREE 读应返回嵌套树且含根条目：%s", items).isNotNull();
        assertThat(root.path("id").asLong()).isEqualTo(rootId);

        // 子条目嵌套于根条目 children（非平铺并列）
        JsonNode childInRoot = findByValue(root.path("children"), childItemValue);
        assertThat(childInRoot).as("子条目应嵌套于根条目 children").isNotNull();
        assertThat(childInRoot.path("id").asLong()).isEqualTo(childId);

        // children 恒数组：叶子 children=[]，根 children 非空数组
        assertThat(root.path("children").isArray())
                .as("children 恒数组（DTO 兜底归一，叶子 []）").isTrue();
        assertThat(childInRoot.path("children").isArray()).isTrue();
        assertThat(childInRoot.path("children").size())
                .as("叶子节点 children=[]").isZero();
        assertThat(root.path("children").size()).as("根节点 children 非空").isPositive();

        // 顶层仅根（子条目不得在顶层并列出现）
        assertThat(countTopLevelMatches(items, childItemValue))
                .as("子条目不应在顶层平铺重复出现").isZero();
    }

    // ---------- AC-17：TREE 写入校验（父存在） ----------

    @Test
    void should_reject_when_tree_parent_missing() throws Exception {
        long treeTypeId = insertTreeType();
        JsonNode rejected = createItem(treeTypeId, "孤儿条目", orphanValue(), 923080599999L);
        assertRejected(rejected, "TREE 父条目不存在");
    }

    // ---------- AC-17：TREE 写入校验（父必属同字典类型） ----------

    @Test
    void should_reject_when_tree_parent_belongs_to_foreign_type() throws Exception {
        long treeTypeId = insertTreeType();
        long flatTypeId = insertFlatType();
        long foreignItemId = insertFlatItem(flatTypeId);

        JsonNode rejected = createItem(treeTypeId, "跨类型条目", crossValue(), foreignItemId);
        assertRejected(rejected, "TREE 父条目属其他字典类型");
    }

    // ---------- AC-17：TREE 写入校验（防环：自环/子孙环，拒绝后关系不变） ----------

    @Test
    void should_reject_cycle_and_keep_parent_chain_when_tree_update_reparents_to_self_or_descendant()
            throws Exception {
        long treeTypeId = insertTreeType();
        String rootItemValue = rootValue();
        String childItemValue = childValue();
        long rootId = createItemOk(treeTypeId, "根条目", rootItemValue, null);
        long childId = createItemOk(treeTypeId, "子条目", childItemValue, rootId);

        // 自环：子条目父指向自身
        JsonNode selfRejected = updateItemParent(treeTypeId, childId, childId);
        assertRejected(selfRejected, "TREE 更新父条目=自身（自环）");

        // 子孙环：根条目父指向其子（沿父链命中自身）
        JsonNode cycleRejected = updateItemParent(treeTypeId, rootId, childId);
        assertRejected(cycleRejected, "TREE 更新父条目=子孙（防环）");

        // 拒绝后父子关系不变（读侧回归）
        JsonNode items = getItems(treeTypeId);
        JsonNode childAfter = findByValue(findByValue(items, rootItemValue).path("children"), childItemValue);
        assertThat(childAfter).as("拒绝后子条目仍嵌套于根条目").isNotNull();
    }

    // ---------- AC-17：FLAT 提交 parentId → PARAM_INVALID 族 ----------

    @Test
    void should_reject_parent_id_when_flat_type_given() throws Exception {
        long treeTypeId = insertTreeType();
        long rootId = createItemOk(treeTypeId, "根条目", rootValue(), null);
        long flatTypeId = insertFlatType();

        JsonNode rejected = createItem(flatTypeId, "平铺带父条目", flatWithParentValue(), rootId);
        assertRejected(rejected, "FLAT 类型提交 parentId");
    }

    // ---------- AC-17：FLAT 回归锚点——既有平铺行为零变化 ----------

    @Test
    void should_keep_flat_behavior_when_flat_type_items_created_and_listed() throws Exception {
        long flatTypeId = insertFlatType();
        String firstValue = "flat-a-" + nextSeq();
        String secondValue = "flat-b-" + nextSeq();

        // 不带 parentId 创建 → 成功（code="0"）；响应信封形状与现状一致
        JsonNode first = createItem(flatTypeId, "平铺条目甲", firstValue, null);
        assertThat(first.path("success").asBoolean()).as("FLAT 不带 parentId 创建应成功：%s", first).isTrue();
        assertThat(first.path("code").asText()).isEqualTo("0");
        createItemOk(flatTypeId, "平铺条目乙", secondValue, null);

        // 平铺读：全部顶层并列、不携带 children 键（零变化锚）
        JsonNode items = getItems(flatTypeId);
        assertThat(items.isArray()).as("FLAT 读应为平铺数组").isTrue();
        assertThat(findByValue(items, firstValue)).as("FLAT 读应含条目甲").isNotNull();
        assertThat(findByValue(items, secondValue)).as("FLAT 读应含条目乙").isNotNull();
        for (JsonNode record : items) {
            assertThat(record.path("children").isMissingNode())
                    .as("FLAT 平铺读不应披露 children 键（平铺行为零变化）").isTrue();
        }
    }

    // ---------- 夹具装配 ----------

    private long insertTreeType() {
        long id = DICT_ID_BASE + (++seq) * 100;
        jdbcTemplate.update(INSERT_DICT_TYPE_TREE, id, FIXTURE_PREFIX + "-tree-" + nextSeq(),
                FIXTURE_PREFIX + " 树形类型");
        return id;
    }

    private long insertFlatType() {
        long id = DICT_ID_BASE + (++seq) * 100;
        jdbcTemplate.update(INSERT_DICT_TYPE_FLAT, id, FIXTURE_PREFIX + "-flat-" + nextSeq(),
                FIXTURE_PREFIX + " 平铺类型");
        return id;
    }

    private long insertFlatItem(long typeId) {
        long id = DICT_ID_BASE + (++seq) * 100 + 1;
        jdbcTemplate.update(INSERT_DICT_ITEM, id, typeId, FIXTURE_PREFIX + " 平铺夹具条目",
                "flat-item-" + nextSeq());
        return id;
    }

    // ---------- 条目读写支撑（AMB-10/AMB-11/AMB-12） ----------

    /** 条目 create 成功路径：断言信封后经读侧按 value 解析回 id。 */
    private long createItemOk(long typeId, String label, String itemValue, Long parentId) throws Exception {
        JsonNode result = createItem(typeId, label, itemValue, parentId);
        assertThat(result.path("success").asBoolean())
                .as("前置：TREE 条目创建应成功：%s", result).isTrue();
        assertThat(result.path("code").asText()).isEqualTo("0");
        return resolveItemId(typeId, itemValue);
    }

    /** 条目 create（AMB-10 path / AMB-11 字段口径）；返回原始信封供正反两向断言。 */
    private JsonNode createItem(long typeId, String label, String itemValue, Long parentId) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("itemLabel", label);
        body.put("itemValue", itemValue);
        body.put("sortOrder", 0);
        if (parentId != null) {
            body.put("parentId", String.valueOf(parentId));
        }
        return callAsSuperAdmin(post(DICT_TYPES_URL + "/" + typeId + "/items")
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    /** 条目 update 改父（AMB-12：version 从同类型条目读侧披露读取后随体回传，未披露则省略）。 */
    private JsonNode updateItemParent(long typeId, long itemId, long newParentId) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("itemLabel", FIXTURE_PREFIX + " 改父夹具");
        body.put("itemValue", "reparent-" + nextSeq());
        body.put("sortOrder", 0);
        body.put("parentId", String.valueOf(newParentId));
        String version = findVersionById(getItems(typeId), itemId);
        if (version != null) {
            body.put("version", version);
        }
        return callAsSuperAdmin(put(DICT_ITEMS_URL + "/" + itemId)
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    private String findVersionById(JsonNode node, long itemId) {
        if (node == null || !(node.isArray() || node.isObject())) {
            return null;
        }
        if (node.isObject()) {
            if (node.path("id").asLong(-1) == itemId) {
                return node.path("version").isMissingNode() || node.path("version").isNull()
                        ? null : node.path("version").asText();
            }
            return findVersionById(node.path("children"), itemId);
        }
        for (JsonNode child : node) {
            String hit = findVersionById(child, itemId);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private JsonNode getItems(long typeId) throws Exception {
        JsonNode body = callAsSuperAdmin(get(DICT_TYPES_URL + "/" + typeId + "/items"));
        assertThat(body.path("success").asBoolean()).as("条目读应成功：%s", body).isTrue();
        assertThat(body.path("code").asText()).isEqualTo("0");
        return body.path("data");
    }

    private long resolveItemId(long typeId, String itemValue) throws Exception {
        JsonNode items = getItems(typeId);
        JsonNode hit = findByValue(items, itemValue);
        assertThat(hit).as("条目读应含新建条目（value=%s）：%s", itemValue, items).isNotNull();
        return hit.path("id").asLong();
    }

    // ---------- 断言与查找支撑 ----------

    private void assertRejected(JsonNode body, String what) {
        assertThat(body.path("success").asBoolean()).as(what + " 应被拒绝：%s", body).isFalse();
        assertThat(body.path("code").asText())
                .as(what + " 业务错误=统一响应体 Result.code≠0（PARAM_INVALID 族，AMB-13）")
                .isNotEqualTo("0");
    }

    /** 按 itemValue 在平铺数组/嵌套 children 树中递归查找。 */
    private JsonNode findByValue(JsonNode node, String itemValue) {
        if (node == null || !(node.isArray() || node.isObject())) {
            return null;
        }
        if (node.isObject()) {
            if (itemValue.equals(node.path("itemValue").asText(null))) {
                return node;
            }
            return findByValue(node.path("children"), itemValue);
        }
        for (JsonNode child : node) {
            JsonNode hit = findByValue(child, itemValue);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private int countTopLevelMatches(JsonNode items, String itemValue) {
        int count = 0;
        for (JsonNode record : items) {
            if (itemValue.equals(record.path("itemValue").asText(null))) {
                count++;
            }
        }
        return count;
    }

    private static int nextSeq() {
        return ++seq;
    }

    private String rootValue() {
        return "tree-root-" + nextSeq();
    }

    private String childValue() {
        return "tree-child-" + nextSeq();
    }

    private String orphanValue() {
        return "orphan-" + nextSeq();
    }

    private String crossValue() {
        return "cross-" + nextSeq();
    }

    private String flatWithParentValue() {
        return "flat-with-parent-" + nextSeq();
    }

    private JsonNode callAsSuperAdmin(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", tokenIssuer.issue(
                                new AuthenticatedSubject("1", "default", Channel.B, Set.of("super_admin"))))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
