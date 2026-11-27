package com.acme.platform.system;

import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.MenuType;
import com.acme.platform.system.api.dto.AdminMenuView;
import com.acme.platform.system.application.MenuUseCase;
import com.acme.platform.system.domain.SysMenu;
import com.acme.platform.system.infrastructure.repository.SysMenuRepository;
import com.acme.platform.system.interfaces.EntityViews;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261120-213603 菜单筛选服务层集成测试（Testcontainers PG，装配口径照
 * REQ20261119051119ButtonMenuBindIntegrationTest / SystemIntegrationTest；无 Docker 自动跳过）。
 *
 * <p>覆盖命中语义（契约 §API/AC-2~6/8/9，接口层参数绑定由 REQ20261120213603MenuFilterSurfaceTest 承担）：
 * keyword 对 menuName/path/permissionCode 不区分大小写包含、任一命中即算（component 不在关键字域）；
 * menuType/status/visible 精确匹配；多条件 AND；命中=命中节点∪其全部祖先、子孙不自动带出；
 * 平铺结果 sortOrder asc/id asc。</p>
 *
 * <p>种子策略：每用例以 SEQ 唯一 tag（itf213603tN）建独立子树——tag 只出现在本用例行的
 * menuName/path/permissionCode 中（种子菜单不含），故 tag 锚定筛选的全量断言互不串扰；
 * case 维度用 ASCII（tag/路径/权限码大小写变体）证明，name 维度用中文命中证明。</p>
 *
 * <p>依赖：{@code MenuUseCase.listFiltered(String, MenuType, EnableStatus, Boolean)} 为契约钉死的
 * 集成点（后端工人并行交付）；{@code SysMenu.setStatus}/{@code SysMenuRepository.save} 为既有实体族
 * 惯例面（置停用样例无用例层入口）。</p>
 */
class REQ20261120213603MenuFilterIntegrationTest extends AbstractSystemIntegrationTest {

    private static final String TENANT = "req213603-t1";

    /** V20 种子锚点（ButtonMenuBind 集成测试留档）：BUTTON 13=创建用户（码 system:user:create，父 2=用户管理，祖父 1=系统管理目录）。 */
    private static final long SEED_ROOT = 1L;
    private static final long SEED_USERS_PAGE = 2L;
    private static final long SEED_CREATE_USER_BTN = 13L;
    private static final String SEED_CREATE_USER_CODE = "system:user:create";

    @Autowired
    private MenuUseCase menuUseCase;

    @Autowired
    private SysMenuRepository menuRepository;

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** 每用例唯一 tag：本用例全部行的 menuName 前缀（ASCII 小写），大写变体用于不区分大小写证明。 */
    private String tag;
    private long rootId;
    private long pageId;
    private long buttonId;
    private long hiddenId;
    private long disabledId;
    private long compId;
    private long sibAId;
    private long sibBId;

    @BeforeEach
    void seedMenus() {
        TenantContext.bind(new TenantContext(TENANT));
        tag = "itf213603t" + SEQ.incrementAndGet();
        // 平铺排序锚点：sortOrder 1/2/3/4/5/6/10/10（同 sortOrder 用 id asc 决序，sibA 先建）
        rootId = menuUseCase.create(create(0L, tag + "根目录", MenuType.DIRECTORY,
                "/" + tag + "-root", null, null, 1, true, null)).getId();
        pageId = menuUseCase.create(create(rootId, tag + "用户管理", MenuType.MENU,
                "/" + tag + "-users", null, null, 2, true, tag + ":user:list")).getId();
        buttonId = menuUseCase.create(create(pageId, tag + "新建按钮", MenuType.BUTTON,
                null, null, null, 3, true, tag + ":user:create")).getId();
        hiddenId = menuUseCase.create(create(rootId, tag + "隐藏页", MenuType.MENU,
                "/" + tag + "-hidden", null, null, 4, false, null)).getId();
        SysMenu disabled = menuUseCase.create(create(rootId, tag + "停用页", MenuType.MENU,
                "/" + tag + "-disabled", null, null, 5, true, null));
        disabled.setStatus(EnableStatus.DISABLED);
        menuRepository.save(disabled);
        disabledId = disabled.getId();
        // component 携带专属子串（tag-compidx）：component 不在关键字域（menuName/path/permissionCode）的负向锚点
        compId = menuUseCase.create(create(rootId, tag + "组件页", MenuType.MENU,
                "/" + tag + "-cmp", tag + "-compidx", null, 6, true, null)).getId();
        sibAId = menuUseCase.create(create(rootId, tag + "兄弟甲", MenuType.MENU,
                "/" + tag + "-sib-a", null, null, 10, true, null)).getId();
        sibBId = menuUseCase.create(create(rootId, tag + "兄弟乙", MenuType.MENU,
                "/" + tag + "-sib-b", null, null, 10, true, null)).getId();
    }

    @AfterEach
    void clearContexts() {
        UserContext.clear();
        TenantContext.clear();
    }

    private static com.acme.platform.system.api.dto.AdminMenuCreate create(Long parentId, String name,
            MenuType type, String path, String component, String icon, int sortOrder, boolean visible,
            String permissionCode) {
        return new com.acme.platform.system.api.dto.AdminMenuCreate(
                parentId, name, type, path, component, icon, sortOrder, visible, permissionCode);
    }

    private List<Long> idsOf(String keyword, MenuType menuType, EnableStatus status, Boolean visible) {
        return menuUseCase.listFiltered(keyword, menuType, status, visible)
                .stream().map(SysMenu::getId).toList();
    }

    // ---------- AC-2：keyword 三字段不区分大小写包含、任一命中即算；component 不在关键字域 ----------

    @Test
    void should_matchKeywordOnNamePathAndPermissionCode_when_caseVariantProbed() {
        // menuName 命中（中文）：tag+「用户管理」仅页面行命中 → 命中∪祖先=页面+根
        assertThat(idsOf(tag + "用户管理", null, null, null))
                .containsExactlyInAnyOrder(pageId, rootId);
        // path 命中（大小写变体：小写路径 vs 大写关键字）
        assertThat(idsOf(tag.toUpperCase() + "-USERS", null, null, null))
                .containsExactlyInAnyOrder(pageId, rootId);
        // permissionCode 命中（大小写变体，种子锚点）：码 system:user:create → 按钮 13 ∪ 祖先 2/1
        assertThat(idsOf(SEED_CREATE_USER_CODE.toUpperCase(), null, null, null))
                .containsExactlyInAnyOrder(SEED_CREATE_USER_BTN, SEED_USERS_PAGE, SEED_ROOT);
        // component 不在关键字域：tag-compidx 只出现在 component → 零命中
        assertThat(idsOf(tag + "-compidx", null, null, null)).isEmpty();
    }

    // ---------- AC-3：menuType=BUTTON → 仅按钮节点及其祖先链 ----------

    @Test
    void should_returnButtonNodesWithAncestorChain_when_menuTypeFilter() {
        List<SysMenu> result = menuUseCase.listFiltered(null, MenuType.BUTTON, null, null);
        List<Long> ids = result.stream().map(SysMenu::getId).toList();
        // 我的按钮及其父链（根、页面）在结果中；非按钮非祖先行不带出
        assertThat(ids).contains(buttonId, pageId, rootId);
        assertThat(ids).doesNotContain(hiddenId, disabledId, compId, sibAId, sibBId);
        // 组树视图下我的按钮父链完整嵌套（root → page → button）
        AdminMenuView buttonNode = findNode(EntityViews.menuTree(result, null), buttonId);
        assertThat(buttonNode).as("BUTTON 命中节点在树中").isNotNull();
        assertThat(findNode(EntityViews.menuTree(result, null), pageId)).isNotNull();
        assertThat(findNode(EntityViews.menuTree(result, null), rootId)).isNotNull();
        assertThat(buttonNode.menuType()).isEqualTo(MenuType.BUTTON);
    }

    // ---------- AC-4：status=DISABLED → 仅停用节点及其祖先链 ----------

    @Test
    void should_returnDisabledNodesWithAncestorChain_when_statusFilter() {
        List<SysMenu> result = menuUseCase.listFiltered(null, null, EnableStatus.DISABLED, null);
        List<Long> ids = result.stream().map(SysMenu::getId).toList();
        assertThat(ids).contains(disabledId, rootId);
        assertThat(ids).doesNotContain(pageId, buttonId, hiddenId, compId, sibAId, sibBId);
        assertThat(findNode(EntityViews.menuTree(result, null), disabledId)).isNotNull();
        assertThat(findNode(EntityViews.menuTree(result, null), rootId)).isNotNull();
    }

    // ---------- AC-5：visible=false → 仅隐藏节点及其祖先链 ----------

    @Test
    void should_returnHiddenNodesWithAncestorChain_when_visibleFilter() {
        List<SysMenu> result = menuUseCase.listFiltered(null, null, null, Boolean.FALSE);
        List<Long> ids = result.stream().map(SysMenu::getId).toList();
        assertThat(ids).contains(hiddenId, rootId);
        assertThat(ids).doesNotContain(pageId, buttonId, disabledId, compId, sibAId, sibBId);
        assertThat(findNode(EntityViews.menuTree(result, null), hiddenId)).isNotNull();
        assertThat(findNode(EntityViews.menuTree(result, null), rootId)).isNotNull();
    }

    // ---------- AC-6：多条件 AND 交集 ----------

    @Test
    void should_intersectAllProvidedConditions_when_combinedFilters() {
        // keyword（tag 唯一锚定我的行）+ menuType=BUTTON → 按钮∪祖先（页面、根）
        assertThat(idsOf(tag, MenuType.BUTTON, null, null))
                .containsExactlyInAnyOrder(buttonId, pageId, rootId);
        // + visible=true：按钮可见 → 集不变
        assertThat(idsOf(tag, MenuType.BUTTON, null, Boolean.TRUE))
                .containsExactlyInAnyOrder(buttonId, pageId, rootId);
        // + visible=false：按钮可见 → 零命中（条件 AND 收紧）
        assertThat(idsOf(tag, MenuType.BUTTON, null, Boolean.FALSE)).isEmpty();
        // keyword + status=DISABLED → 停用页∪祖先（根）
        assertThat(idsOf(tag, null, EnableStatus.DISABLED, null))
                .containsExactlyInAnyOrder(disabledId, rootId);
        // keyword + menuType=MENU + status=DISABLED + visible=true → 仍仅停用页∪祖先
        assertThat(idsOf(tag, MenuType.MENU, EnableStatus.DISABLED, Boolean.TRUE))
                .containsExactlyInAnyOrder(disabledId, rootId);
    }

    // ---------- AC-8：深层命中祖先链完整、平铺 sortOrder asc/id asc、组树序保持 ----------

    @Test
    void should_keepAncestorChainAndSortOrder_when_filtered() {
        // 深层命中：按钮 ∪ 祖先（页面、根），平铺顺序=sortOrder asc/id asc（1/2/3/4/5/6/10/10，同序 id asc）
        List<Long> ordered = idsOf(tag, null, null, null);
        assertThat(ordered).containsExactly(
                rootId, pageId, buttonId, hiddenId, disabledId, compId, sibAId, sibBId);
        // 组树（Controller 复用的既有组树逻辑）：唯一根=我的根目录；子序=逐层平铺序；button 嵌于 page 下
        List<AdminMenuView> tree = EntityViews.menuTree(
                menuUseCase.listFiltered(tag, null, null, null), null);
        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).id()).isEqualTo(rootId);
        // 根的直接 children=page/hidden/disabled/comp/sibA/sibB（平铺序去根；button 是孙辈不在根下）
        assertThat(tree.get(0).children()).extracting(AdminMenuView::id).containsExactly(
                pageId, hiddenId, disabledId, compId, sibAId, sibBId);
        // AC-8 三层链：root → page → button 逐级嵌套
        assertThat(findNode(tree, pageId).children()).extracting(AdminMenuView::id)
                .containsExactly(buttonId);
        // 命中节点的子孙不自动带出：仅按名命中按钮时，平铺=按钮∪祖先（sortOrder asc/id asc；无其它子孙、无种子行）
        assertThat(idsOf(tag + "新建按钮", null, null, null))
                .containsExactly(rootId, pageId, buttonId);
    }

    // ---------- AC-9：零命中返回空列表 ----------

    @Test
    void should_returnEmptyList_when_nothingMatches() {
        assertThat(idsOf(tag + "-zzz-nohit", null, null, null)).isEmpty();
        assertThat(menuUseCase.listFiltered(tag + "-zzz-nohit", null, null, null)).isEmpty();
    }

    /** 树中按 id 找节点（EntityViews.menuTree 断言用，照 ButtonMenuBind 集成测试同型）。 */
    private static AdminMenuView findNode(List<AdminMenuView> tree, long id) {
        for (AdminMenuView node : tree) {
            if (node.id() == id) {
                return node;
            }
            AdminMenuView found = findNode(node.children(), id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
