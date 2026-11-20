package com.acme.platform.system.interfaces;

import com.acme.framework.audit.support.AuditLog;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.MenuType;
import com.acme.platform.system.api.SystemPermissions;
import com.acme.platform.system.api.dto.AdminMenuCreate;
import com.acme.platform.system.api.dto.AdminMenuUpdate;
import com.acme.platform.system.api.dto.AdminMenuView;
import com.acme.platform.system.application.MenuUseCase;
import com.acme.platform.system.security.RequirePermission;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 菜单管理接口（FR-008 菜单级权限；全量树查询）。
 */
@RestController
@RequestMapping("/admin/v1/system/menus")
public class MenuController {

    private final MenuUseCase menuUseCase;

    public MenuController(MenuUseCase menuUseCase) {
        this.menuUseCase = menuUseCase;
    }

    /**
     * 菜单树查询（REQ-20261120-213603 增补可选筛选，无参调用行为与现状全量树一致）：
     * keyword 对 menuName/path/permissionCode 不区分大小写包含、任一字段命中即算；
     * menuType/status/visible 精确匹配；多条件 AND；返回命中节点∪其全部祖先链组成的树。
     * 四参数全空路由回 listAll() 现状路径（无参调用面零漂移）；任一非空才走 listFiltered(...)。
     * 非法枚举值经框架 GlobalExceptionTranslator（MethodArgumentTypeMismatchException）翻译为
     * HTTP 400 + Result 信封 BAD_REQUEST，不自行捕获。
     */
    @GetMapping
    @RequirePermission(SystemPermissions.MENU_LIST)
    public List<AdminMenuView> tree(@RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) MenuType menuType,
                                    @RequestParam(required = false) EnableStatus status,
                                    @RequestParam(required = false) Boolean visible) {
        if (keyword == null && menuType == null && status == null && visible == null) {
            return EntityViews.menuTree(menuUseCase.listAll(), null);
        }
        return EntityViews.menuTree(menuUseCase.listFiltered(keyword, menuType, status, visible), null);
    }

    @PostMapping
    @RequirePermission(SystemPermissions.MENU_CREATE)
    @AuditLog(action = "system.menu.create", targetType = "menu", contentParams = {"request.menuName"})
    public AdminMenuView create(@Valid @RequestBody AdminMenuCreate request) {
        return EntityViews.flatMenu(menuUseCase.create(request));
    }

    @GetMapping("/{menuId}")
    @RequirePermission(SystemPermissions.MENU_QUERY)
    public AdminMenuView get(@PathVariable Long menuId) {
        return EntityViews.flatMenu(menuUseCase.get(menuId));
    }

    @PutMapping("/{menuId}")
    @RequirePermission(SystemPermissions.MENU_UPDATE)
    @AuditLog(action = "system.menu.update", targetType = "menu", targetId = "#menuId",
            contentParams = {"menuId", "request.menuName"})
    public AdminMenuView update(@PathVariable Long menuId, @Valid @RequestBody AdminMenuUpdate request) {
        return EntityViews.flatMenu(menuUseCase.update(menuId, request));
    }

    @DeleteMapping("/{menuId}")
    @RequirePermission(SystemPermissions.MENU_DELETE)
    @AuditLog(action = "system.menu.delete", targetType = "menu", targetId = "#menuId",
            contentParams = {"menuId"})
    public void delete(@PathVariable Long menuId) {
        menuUseCase.delete(menuId);
    }
}
