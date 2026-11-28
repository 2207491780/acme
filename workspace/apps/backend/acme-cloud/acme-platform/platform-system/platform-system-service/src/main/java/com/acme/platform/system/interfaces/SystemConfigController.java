package com.acme.platform.system.interfaces;

import com.acme.common.core.BizException;
import com.acme.common.core.Result;
import com.acme.framework.audit.support.AuditLog;
import com.acme.framework.flex.support.FlexError;
import com.acme.framework.web.interceptor.GlobalExceptionTranslator;
import com.acme.framework.web.support.WebError;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.SystemPermissions;
import com.acme.platform.system.api.dto.AdminSystemConfigUpdate;
import com.acme.platform.system.api.dto.AdminSystemConfigView;
import com.acme.platform.system.application.SystemConfigUseCase;
import com.acme.framework.web.security.RequirePermission;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户级系统配置接口（REQ-20261116-230354）：GET 认证即可（全员强制生效需读取，无权限标注）；
 * PUT 经 system:config:update 权限点（RequirePermissionInterceptor 强制，无权限 403）。
 */
@RestController
@RequestMapping("/admin/v1/system")
public class SystemConfigController {

    private final SystemConfigUseCase systemConfigUseCase;
    private final GlobalExceptionTranslator exceptionTranslator;

    public SystemConfigController(SystemConfigUseCase systemConfigUseCase,
                                  GlobalExceptionTranslator exceptionTranslator) {
        this.systemConfigUseCase = systemConfigUseCase;
        this.exceptionTranslator = exceptionTranslator;
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Object>> handleConfigError(BizException exception) {
        ResponseEntity<Result<Object>> response = exceptionTranslator.handleBiz(exception);
        if (exception.errorCode() == SystemError.SYSTEM_CONFIG_INVALID
                || exception.errorCode() == SystemError.SYSTEM_WATERMARK_TEMPLATE_INVALID
                || exception.errorCode() == WebError.CONCURRENCY_CONFLICT
                || exception.errorCode() == FlexError.STALE_WRITE) {
            return ResponseEntity.ok(response.getBody());
        }
        return response;
    }

    @GetMapping("/config")
    public AdminSystemConfigView get() {
        return systemConfigUseCase.findCurrent()
                .map(EntityViews::systemConfigView)
                .orElseGet(EntityViews::systemConfigDefaultView);
    }

    @PutMapping("/config")
    @RequirePermission(SystemPermissions.CONFIG_UPDATE)
    @AuditLog(action = "system.config.update", targetType = "systemConfig",
            beforeQuery = "@systemConfigUseCase.findCurrent()",
            contentParams = {"request.watermarkEnabled", "request.captchaEnabled",
                    "request.lockIdleTimeoutMs", "request.footerVisible"})
    public AdminSystemConfigView update(@Valid @RequestBody AdminSystemConfigUpdate request) {
        return EntityViews.systemConfigView(systemConfigUseCase.update(request));
    }
}