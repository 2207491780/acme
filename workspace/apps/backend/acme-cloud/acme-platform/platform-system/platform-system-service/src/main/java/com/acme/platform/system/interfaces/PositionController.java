package com.acme.platform.system.interfaces;

import com.acme.common.core.Result;
import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.SystemPermissions;
import com.acme.platform.system.api.dto.AdminPositionCreate;
import com.acme.platform.system.api.dto.AdminPositionUpdate;
import com.acme.platform.system.api.dto.AdminPositionView;
import com.acme.platform.system.application.PositionUseCase;
import com.acme.platform.system.domain.SysPosition;
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

/**
 * 岗位管理接口（REQ-20261114-120104，权限 system:position:*）：分页 + 增改删（名称/编码/状态）；
 * 被用户引用的岗位删除拒绝（POSITION_IN_USE）。
 */
@RestController
@RequestMapping("/admin/v1/system/positions")
public class PositionController {

    private final PositionUseCase positionUseCase;

    public PositionController(PositionUseCase positionUseCase) {
        this.positionUseCase = positionUseCase;
    }

    @GetMapping
    @RequirePermission(SystemPermissions.POSITION_LIST)
    public PageResult<AdminPositionView> list(@RequestParam(defaultValue = "1") Integer page,
                                              @RequestParam(defaultValue = "20") Integer size,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) EnableStatus status) {
        PageResult<SysPosition> result = positionUseCase.page(new PageQuery(page, size), keyword, status);
        return new PageResult<>(
                result.records().stream().map(EntityViews::positionView).toList(), result.total());
    }

    @PostMapping
    @RequirePermission(SystemPermissions.POSITION_CREATE)
    public AdminPositionView create(@Valid @RequestBody AdminPositionCreate request) {
        return EntityViews.positionView(positionUseCase.create(request));
    }

    @GetMapping("/{positionId}")
    @RequirePermission(SystemPermissions.POSITION_LIST)
    public AdminPositionView get(@PathVariable Long positionId) {
        return EntityViews.positionView(positionUseCase.get(positionId));
    }

    @PutMapping("/{positionId}")
    @RequirePermission(SystemPermissions.POSITION_UPDATE)
    public AdminPositionView update(@PathVariable Long positionId,
                                    @Valid @RequestBody AdminPositionUpdate request) {
        return EntityViews.positionView(positionUseCase.update(positionId, request));
    }

    /** 启用/停用（统一响应体 Result 契约口径：void 语义显式出信封，code="0"）。 */
    @PutMapping("/{positionId}/status")
    @RequirePermission(SystemPermissions.POSITION_UPDATE)
    public Result<Void> updateStatus(@PathVariable Long positionId,
                                     @Valid @RequestBody com.acme.platform.system.api.dto.AdminStatusUpdate request) {
        positionUseCase.updateStatus(positionId, request.status());
        return Result.ok(null);
    }

    /** 删除岗位（统一响应体 Result 契约口径：void 语义显式出信封，code="0"）。 */
    @DeleteMapping("/{positionId}")
    @RequirePermission(SystemPermissions.POSITION_DELETE)
    public Result<Void> delete(@PathVariable Long positionId) {
        positionUseCase.delete(positionId);
        return Result.ok(null);
    }
}
