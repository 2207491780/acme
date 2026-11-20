package com.acme.platform.system.interfaces;

import com.acme.platform.system.api.SystemPermissions;
import com.acme.platform.system.api.dto.AdminCapabilityView;
import com.acme.platform.system.application.SystemCapabilityUseCase;
import com.acme.platform.system.security.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 服务能力目录接口（REQ-20261121-054613 确认③④，套餐表单「能力」checkbox 组数据源）：
 * 目录读取挂既有 system:tenant-package:list 权限——能力是套餐管理面的组成部分，
 * 不新增菜单节点/权限点（避免权限水位再膨胀）；仅返回 status=ENABLED 能力，id asc。
 */
@RestController
@RequestMapping("/admin/v1/system/capabilities")
public class CapabilityController {

    private final SystemCapabilityUseCase systemCapabilityUseCase;

    public CapabilityController(SystemCapabilityUseCase systemCapabilityUseCase) {
        this.systemCapabilityUseCase = systemCapabilityUseCase;
    }

    /** 全量启用能力目录（id asc；权限沿用 system:tenant-package:list，确认③）。 */
    @GetMapping
    @RequirePermission(SystemPermissions.TENANT_PACKAGE_LIST)
    public List<AdminCapabilityView> list() {
        return systemCapabilityUseCase.listEnabled().stream()
                .map(EntityViews::capabilityView)
                .toList();
    }
}
