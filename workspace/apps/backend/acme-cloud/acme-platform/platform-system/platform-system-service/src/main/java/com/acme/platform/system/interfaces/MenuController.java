package com.acme.platform.system.interfaces;

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

    @GetMapping
    @RequirePermission(SystemPermissions.MENU_LIST)
    public List<AdminMenuView> tree() {
        return EntityViews.menuTree(menuUseCase.listAll(), null);
    }

    @PostMapping
    @RequirePermission(SystemPermissions.MENU_CREATE)
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
    public AdminMenuView update(@PathVariable Long menuId, @Valid @RequestBody AdminMenuUpdate request) {
        return EntityViews.flatMenu(menuUseCase.update(menuId, request));
    }

    @DeleteMapping("/{menuId}")
    @RequirePermission(SystemPermissions.MENU_DELETE)
    public void delete(@PathVariable Long menuId) {
        menuUseCase.delete(menuId);
    }
}
