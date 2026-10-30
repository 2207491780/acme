package com.acme.platform.system.interfaces;

import com.acme.platform.system.api.dto.AdminMeView;
import com.acme.platform.system.api.dto.AdminUserView;
import com.acme.platform.system.application.MeUseCase;
import com.acme.platform.system.application.MenuUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前登录者视图接口（FR-008 权限声明与菜单可见性）：认证即可访问（无独立权限点标注）。
 */
@RestController
@RequestMapping("/admin/v1/system/me")
public class MeController {

    private final MeUseCase meUseCase;
    private final MenuUseCase menuUseCase;

    public MeController(MeUseCase meUseCase, MenuUseCase menuUseCase) {
        this.meUseCase = meUseCase;
        this.menuUseCase = menuUseCase;
    }

    @GetMapping
    public AdminMeView me() {
        MeUseCase.MeSnapshot snapshot = meUseCase.loadMe();
        AdminUserView user = EntityViews.userView(snapshot.user(), java.util.List.of());
        return new AdminMeView(user, java.util.List.copyOf(snapshot.roles()),
                java.util.List.copyOf(snapshot.permissions()),
                EntityViews.menuTree(menuUseCase.listAll(), snapshot.menuIds()));
    }
}
