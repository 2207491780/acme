package com.acme.platform.system.interfaces;

import com.acme.platform.system.api.dto.AdminMeUpdate;
import com.acme.platform.system.api.dto.AdminMeView;
import com.acme.platform.system.api.dto.AdminUserView;
import com.acme.platform.system.application.MeUseCase;
import com.acme.platform.system.application.MenuUseCase;
import com.acme.platform.system.application.SystemCapabilityUseCase;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前登录者视图接口（FR-008 权限声明与菜单可见性）：认证即可访问（无独立权限点标注）。
 * REQ-20261119-082531：增自助资料修改 PUT /me（确认记录①，认证即可；avatarUrl 由前端把
 * platform-infra 头像上传端点产出的 URL 回填，TL 决策①——system 域无存储依赖）。
 * REQ-20261121-054613 确认⑤：响应增 capabilities=当前租户生效能力码（web 特性开关数据源，
 * 未绑定套餐=全部启用能力码；解析在接口层组装——11.5.3 转换在接口层完成）。
 */
@RestController
@RequestMapping("/admin/v1/system/me")
public class MeController {

    private final MeUseCase meUseCase;
    private final MenuUseCase menuUseCase;
    private final SystemCapabilityUseCase systemCapabilityUseCase;

    public MeController(MeUseCase meUseCase, MenuUseCase menuUseCase,
                        SystemCapabilityUseCase systemCapabilityUseCase) {
        this.meUseCase = meUseCase;
        this.menuUseCase = menuUseCase;
        this.systemCapabilityUseCase = systemCapabilityUseCase;
    }

    @GetMapping
    public AdminMeView me() {
        return viewOf(meUseCase.loadMe());
    }

    /** 本人资料修改（校验对齐确认记录①：nickname ≤32 必填 / email 格式可空 / phone 1\d{10} 可空）。 */
    @PutMapping
    public AdminMeView updateProfile(@Valid @RequestBody AdminMeUpdate request) {
        return viewOf(meUseCase.updateProfile(request));
    }

    /** 快照 → 视图组装（11.5.3 转换在接口层；GET/PUT 共用同一形态，前端 meStore 单一刷新口径）。 */
    private AdminMeView viewOf(MeUseCase.MeSnapshot snapshot) {
        AdminUserView user = EntityViews.userView(snapshot.user(), java.util.List.of());
        return new AdminMeView(user, java.util.List.copyOf(snapshot.roles()),
                java.util.List.copyOf(snapshot.permissions()),
                EntityViews.menuTree(menuUseCase.listAll(), snapshot.menuIds()), snapshot.deptName(),
                systemCapabilityUseCase.currentTenantCapabilityCodes());
    }
}
