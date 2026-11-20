package com.acme.platform.system.interfaces;

import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.framework.core.support.PromptLocalizer;
import com.acme.framework.web.interceptor.GlobalExceptionTranslator;
import com.acme.framework.web.interceptor.StandardResponseAdvice;
import com.acme.framework.web.support.WebError;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.MenuType;
import com.acme.platform.system.application.MenuUseCase;
import com.acme.platform.system.domain.SysMenu;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * REQ-20261120-213603 菜单筛选接口层测试（GET /admin/v1/system/menus，新增可选 query 参数）。
 *
 * <p>契约口径（contracts/REQ-20261120-213603.md §API）：keyword/menuType/status/visible 全部可选，
 * 多条件 AND；无参数=现状全量树；命中=命中节点∪其全部祖先（祖先链组装在 service 层完成，
 * Controller 以 {@code EntityViews.menuTree(listFiltered(...), null)} 组树）；
 * 服务层集成点钉死为 {@code MenuUseCase.listFiltered(String, MenuType, EnableStatus, Boolean)}；
 * 非法枚举值→HTTP 400＋Result 信封 WebError.BAD_REQUEST（框架既有翻译器行为，不新增错误码）。</p>
 *
 * <p>装配照 SystemApiSurfaceTest：用例打桩＋standalone MockMvc＋真实 GlobalExceptionTranslator
 * （HTTP 切片承担参数绑定/枚举转换/信封出参；命中过滤语义本身由
 * REQ20261120213603MenuFilterIntegrationTest 在真实库上验证）。</p>
 *
 * <p>依赖：MenuUseCase.listFiltered 为并行交付的契约集成点——后端工人落地前本文件不参与编译，
 * 由 TL 在后端交付后统一执行。</p>
 */
class REQ20261120213603MenuFilterSurfaceTest {

    private static final long ROOT = 1L;
    private static final long USERS = 2L;
    private static final long ROLES = 3L;
    private static final long DELETE_BTN = 4L;

    @AfterEach
    void cleanup() {
        UserContext.clear();
        TenantContext.clear();
    }

    // ---------- fixtures（桩用例返回的平铺命中集；排序责任在 service，桩即按契约排序给出） ----------

    /** 命中链样例：根目录 → 用户管理/角色管理 → 删除角色按钮（sortOrder 1/1,2/1，深按钮挂在角色管理下）。 */
    private static List<SysMenu> hitChainSorted() {
        return List.of(
                menu(ROOT, 0L, "系统管理", MenuType.DIRECTORY, "/system", 1, true, null),
                menu(USERS, ROOT, "用户管理", MenuType.MENU, "/users", 1, true, "system:user:list"),
                menu(ROLES, ROOT, "角色管理", MenuType.MENU, "/roles", 2, true, null),
                menu(DELETE_BTN, ROLES, "删除角色按钮", MenuType.BUTTON, null, 1, true, "system:role:delete"));
    }

    private static SysMenu menu(long id, long parentId, String name, MenuType type,
                                String path, int sortOrder, boolean visible, String permissionCode) {
        SysMenu menu = new SysMenu(parentId, name, type, path, null, null, sortOrder, visible, permissionCode);
        try {
            var field = com.acme.framework.flex.support.AuditBase.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(menu, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return menu;
    }

    private static GlobalExceptionTranslator translator() {
        return new GlobalExceptionTranslator(
                new StaticListableBeanFactory().getBeanProvider(PromptLocalizer.class));
    }

    private static MockMvc mvcFor(MenuUseCase useCase) {
        return MockMvcBuilders.standaloneSetup(new MenuController(useCase))
                .setControllerAdvice(translator(),
                        new StandardResponseAdvice(tools.jackson.databind.json.JsonMapper.builder().build()))
                .build();
    }

    private static tools.jackson.databind.JsonNode jsonBody(MvcResult result) {
        return tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(result.getResponse().getContentAsByteArray());
    }

    private static MvcResult performGet(MockMvc mvc, String... params) throws Exception {
        var request = get("/admin/v1/system/menus");
        for (int i = 0; i + 1 < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request).andReturn();
    }

    private static void assertSuccessEnvelope(MvcResult result) {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        var body = jsonBody(result);
        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(body.path("code").asText()).isEqualTo("0");
        assertThat(body.path("data").isArray()).isTrue();
    }

    // ---------- AC-1：无参=现状全量树（回归兼容；page/size 可选携带、不分页） ----------

    @Test
    void should_returnFullTree_when_noFilterParams() throws Exception {
        MenuUseCase useCase = mock(MenuUseCase.class);
        // 无参路由（listAll 现状路径 / listFiltered 全空参路径）契约未钉死其一：两路都桩同一全量集，
        // 只断言端点行为=全量树，不锁内部路由
        when(useCase.listAll()).thenReturn(hitChainSorted());
        when(useCase.listFiltered(any(), any(), any(), any())).thenReturn(hitChainSorted());
        MockMvc mvc = mvcFor(useCase);

        MvcResult result = performGet(mvc);
        assertSuccessEnvelope(result);
        var data = jsonBody(result).path("data");
        assertThat(data.size()).isEqualTo(1);
        assertThat(data.get(0).path("menuName").asText()).isEqualTo("系统管理");
        var children = data.get(0).path("children");
        assertThat(children.size()).isEqualTo(2);
        assertThat(children.get(0).path("menuName").asText()).isEqualTo("用户管理");
        assertThat(children.get(1).path("menuName").asText()).isEqualTo("角色管理");

        // page/size 可选携带、不分页（现状口径）：携带后行为不变
        MvcResult paged = performGet(mvc, "page", "1", "size", "20");
        assertSuccessEnvelope(paged);
        assertThat(jsonBody(paged).path("data").size()).isEqualTo(1);
    }

    // ---------- AC-2：keyword 透传（三字段不区分大小写包含的命中语义在集成测试验证） ----------

    @Test
    void should_passKeywordToListFiltered_when_keywordParam() throws Exception {
        MenuUseCase useCase = mock(MenuUseCase.class);
        when(useCase.listFiltered(any(), any(), any(), any())).thenReturn(hitChainSorted());
        MockMvc mvc = mvcFor(useCase);

        MvcResult result = performGet(mvc, "keyword", "用户");
        assertSuccessEnvelope(result);
        var data = jsonBody(result).path("data");
        assertThat(data.size()).isEqualTo(1);
        assertThat(data.get(0).path("menuName").asText()).isEqualTo("系统管理");
        assertThat(data.get(0).path("children").get(0).path("menuName").asText()).isEqualTo("用户管理");

        ArgumentCaptor<String> keyword = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MenuType> type = ArgumentCaptor.forClass(MenuType.class);
        ArgumentCaptor<EnableStatus> status = ArgumentCaptor.forClass(EnableStatus.class);
        ArgumentCaptor<Boolean> visible = ArgumentCaptor.forClass(Boolean.class);
        verify(useCase).listFiltered(keyword.capture(), type.capture(), status.capture(), visible.capture());
        assertThat(keyword.getValue()).isEqualTo("用户");
        assertThat(type.getValue()).isNull();
        assertThat(status.getValue()).isNull();
        assertThat(visible.getValue()).isNull();
    }

    // ---------- AC-3：menuType 精确匹配参数绑定 ----------

    @Test
    void should_passMenuTypeToListFiltered_when_menuTypeParam() throws Exception {
        MenuUseCase useCase = mock(MenuUseCase.class);
        when(useCase.listFiltered(any(), any(), any(), any())).thenReturn(hitChainSorted());
        MockMvc mvc = mvcFor(useCase);

        MvcResult result = performGet(mvc, "menuType", "BUTTON");
        assertSuccessEnvelope(result);
        var data = jsonBody(result).path("data");
        assertThat(data.get(0).path("children").get(1).path("children").get(0).path("menuType").asText())
                .isEqualTo("BUTTON");

        ArgumentCaptor<String> keyword = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MenuType> type = ArgumentCaptor.forClass(MenuType.class);
        ArgumentCaptor<EnableStatus> status = ArgumentCaptor.forClass(EnableStatus.class);
        ArgumentCaptor<Boolean> visible = ArgumentCaptor.forClass(Boolean.class);
        verify(useCase).listFiltered(keyword.capture(), type.capture(), status.capture(), visible.capture());
        assertThat(keyword.getValue()).isNull();
        assertThat(type.getValue()).isEqualTo(MenuType.BUTTON);
        assertThat(status.getValue()).isNull();
        assertThat(visible.getValue()).isNull();
    }

    // ---------- AC-4：status 精确匹配参数绑定 ----------

    @Test
    void should_passStatusToListFiltered_when_statusParam() throws Exception {
        MenuUseCase useCase = mock(MenuUseCase.class);
        when(useCase.listFiltered(any(), any(), any(), any())).thenReturn(hitChainSorted());
        MockMvc mvc = mvcFor(useCase);

        MvcResult result = performGet(mvc, "status", "DISABLED");
        assertSuccessEnvelope(result);

        ArgumentCaptor<String> keyword = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MenuType> type = ArgumentCaptor.forClass(MenuType.class);
        ArgumentCaptor<EnableStatus> status = ArgumentCaptor.forClass(EnableStatus.class);
        ArgumentCaptor<Boolean> visible = ArgumentCaptor.forClass(Boolean.class);
        verify(useCase).listFiltered(keyword.capture(), type.capture(), status.capture(), visible.capture());
        assertThat(keyword.getValue()).isNull();
        assertThat(type.getValue()).isNull();
        assertThat(status.getValue()).isEqualTo(EnableStatus.DISABLED);
        assertThat(visible.getValue()).isNull();
    }

    // ---------- AC-5：visible 精确匹配参数绑定 ----------

    @Test
    void should_passVisibleToListFiltered_when_visibleParam() throws Exception {
        MenuUseCase useCase = mock(MenuUseCase.class);
        when(useCase.listFiltered(any(), any(), any(), any())).thenReturn(hitChainSorted());
        MockMvc mvc = mvcFor(useCase);

        MvcResult result = performGet(mvc, "visible", "false");
        assertSuccessEnvelope(result);

        ArgumentCaptor<String> keyword = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MenuType> type = ArgumentCaptor.forClass(MenuType.class);
        ArgumentCaptor<EnableStatus> status = ArgumentCaptor.forClass(EnableStatus.class);
        ArgumentCaptor<Boolean> visible = ArgumentCaptor.forClass(Boolean.class);
        verify(useCase).listFiltered(keyword.capture(), type.capture(), status.capture(), visible.capture());
        assertThat(keyword.getValue()).isNull();
        assertThat(type.getValue()).isNull();
        assertThat(status.getValue()).isNull();
        assertThat(visible.getValue()).isEqualTo(Boolean.FALSE);
    }

    // ---------- AC-6：多条件组合全部解析透传（AND 交集语义在集成测试验证） ----------

    @Test
    void should_passAllFourFiltersToListFiltered_when_combinedParams() throws Exception {
        MenuUseCase useCase = mock(MenuUseCase.class);
        when(useCase.listFiltered(any(), any(), any(), any())).thenReturn(hitChainSorted());
        MockMvc mvc = mvcFor(useCase);

        MvcResult result = performGet(mvc,
                "keyword", "用户", "menuType", "MENU", "status", "DISABLED", "visible", "true");
        assertSuccessEnvelope(result);

        ArgumentCaptor<String> keyword = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MenuType> type = ArgumentCaptor.forClass(MenuType.class);
        ArgumentCaptor<EnableStatus> status = ArgumentCaptor.forClass(EnableStatus.class);
        ArgumentCaptor<Boolean> visible = ArgumentCaptor.forClass(Boolean.class);
        verify(useCase).listFiltered(keyword.capture(), type.capture(), status.capture(), visible.capture());
        assertThat(keyword.getValue()).isEqualTo("用户");
        assertThat(type.getValue()).isEqualTo(MenuType.MENU);
        assertThat(status.getValue()).isEqualTo(EnableStatus.DISABLED);
        assertThat(visible.getValue()).isEqualTo(Boolean.TRUE);
    }

    // ---------- AC-7：非法枚举值 → HTTP 400＋Result 信封 BAD_REQUEST，不 500 ----------

    @Test
    void should_returnBadRequestEnvelope_when_illegalMenuTypeParam() throws Exception {
        MenuUseCase useCase = mock(MenuUseCase.class);
        MockMvc mvc = mvcFor(useCase);

        MvcResult result = performGet(mvc, "menuType", "FOO");
        assertThat(result.getResponse().getStatus()).as("非法枚举必须 400 而非 500").isEqualTo(400);
        var body = jsonBody(result);
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("code").asText()).isEqualTo(WebError.BAD_REQUEST.code());
    }

    @Test
    void should_returnBadRequestEnvelope_when_illegalVisibleParam() throws Exception {
        MenuUseCase useCase = mock(MenuUseCase.class);
        MockMvc mvc = mvcFor(useCase);

        MvcResult result = performGet(mvc, "visible", "abc");
        assertThat(result.getResponse().getStatus()).as("非法布尔必须 400 而非 500").isEqualTo(400);
        var body = jsonBody(result);
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("code").asText()).isEqualTo(WebError.BAD_REQUEST.code());
    }

    // ---------- AC-8：祖先链完整组装＋排序保持（组树复用既有逻辑；命中筛选语义在集成测试验证） ----------

    @Test
    void should_assembleCompleteAncestorTreeInServiceOrder_when_deepHitReturned() throws Exception {
        MenuUseCase useCase = mock(MenuUseCase.class);
        // service 按契约返回平铺命中集（sortOrder asc/id asc）：根 → 用户管理(sort1)/角色管理(sort2) → 按钮
        when(useCase.listFiltered(any(), any(), any(), any())).thenReturn(hitChainSorted());
        MockMvc mvc = mvcFor(useCase);

        MvcResult result = performGet(mvc, "keyword", "删除角色按钮");
        assertSuccessEnvelope(result);
        var data = jsonBody(result).path("data");
        // 根→…→父逐级保留，父子层级组装正确
        assertThat(data.size()).isEqualTo(1);
        assertThat(data.get(0).path("menuName").asText()).isEqualTo("系统管理");
        var children = data.get(0).path("children");
        assertThat(children.size()).isEqualTo(2);
        // 子节点顺序=service 平铺序（sortOrder asc）
        assertThat(children.get(0).path("menuName").asText()).isEqualTo("用户管理");
        assertThat(children.get(1).path("menuName").asText()).isEqualTo("角色管理");
        // 深层命中节点的父链完整
        var button = children.get(1).path("children");
        assertThat(button.size()).isEqualTo(1);
        assertThat(button.get(0).path("menuName").asText()).isEqualTo("删除角色按钮");
        assertThat(button.get(0).path("menuType").asText()).isEqualTo("BUTTON");
        assertThat(button.get(0).path("permissionCode").asText()).isEqualTo("system:role:delete");
    }

    // ---------- AC-9：空命中返回空列表（code=0 正常响应，非报错） ----------

    @Test
    void should_returnEmptyListEnvelope_when_noHit() throws Exception {
        MenuUseCase useCase = mock(MenuUseCase.class);
        when(useCase.listFiltered(any(), any(), any(), any())).thenReturn(List.of());
        MockMvc mvc = mvcFor(useCase);

        MvcResult result = performGet(mvc, "keyword", "绝不存在的关键字xyz");
        assertSuccessEnvelope(result);
        assertThat(jsonBody(result).path("data").size()).isZero();
    }
}
