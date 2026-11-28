package com.acme.platform.infra.interfaces;

import com.acme.common.context.UserContext;
import com.acme.common.core.Result;
import com.acme.framework.audit.support.AuditLog;
import com.acme.platform.infra.api.dto.DirectUploadPreparationView;
import com.acme.platform.infra.api.dto.FileView;
import com.acme.platform.infra.application.FileUseCase;
import com.acme.platform.infra.infrastructure.redis.UploadTicketStore;
import com.acme.framework.web.security.RequirePermission;
import com.acme.platform.system.api.SystemPermissions;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;

/**
 * 文件直传 B 端接口（REQ-20261117-045754 §API，权限点 infra:file:upload）：
 * 直传统一走同源——LOCAL 模式返回服务端一次性票据 PUT 端点（走既有认证面），MINIO/OSS 返回
 * 预签名 PUT URL（Content-Type 头随签名，部署侧同源反代，本 REQ 不部署不实测）。
 * 三段：preparations（校验 + 发票/预签名）→ PUT 原始字节 → completions（校验 + 落 sys_file）。
 * multipart {@code POST ""} 端点保留不动（兼容），见 {@link FileController}。
 */
@RestController
@RequestMapping("/admin/v1/infra/files")
public class DirectUploadController {

    private final FileUseCase fileUseCase;

    public DirectUploadController(FileUseCase fileUseCase) {
        this.fileUseCase = fileUseCase;
    }

    /** 直传准备（校验链 → 带租户段 objectKey → LOCAL 票据 / MINIO-OSS 预签名；写端点纳入审计）。 */
    @PostMapping("/direct-upload-preparations")
    @RequirePermission(SystemPermissions.INFRA_FILE_UPLOAD)
    @AuditLog(action = "infra.directUpload.prepare", targetType = "file",
            contentParams = {"request.fileName", "request.contentType", "request.size"})
    public DirectUploadPreparationView prepare(@Valid @RequestBody PreparationRequest request) {
        return fileUseCase.prepareDirectUpload(request.fileName(), request.contentType(), request.size(),
                request.bizType(), request.bizId(), currentUserId());
    }

    /**
     * 票据直传（LOCAL 模式二段；MINIO/OSS 模式无此路径，客户端直 PUT 预签名 URL）：
     * 原始字节体（非 multipart），Content-Type 头须与 preparation 一致；成功 200 空 Result（纳入审计）。
     */
    @PutMapping("/direct-uploads/{ticket}")
    @RequirePermission(SystemPermissions.INFRA_FILE_UPLOAD)
    @AuditLog(action = "infra.directUpload.upload", targetType = "file", contentParams = {"ticket"})
    public Result<Void> upload(@PathVariable String ticket, HttpServletRequest request) throws IOException {
        UploadTicketStore.Ticket payload = fileUseCase.consumeTicket(ticket, currentUserId());
        try (InputStream body = request.getInputStream()) {
            fileUseCase.putDirectUpload(payload, body,
                    request.getContentLengthLong(), request.getContentType());
        }
        return Result.ok(null);
    }

    /** 直传完成（租户归属 → 幂等 → exists → size 一致 → 图片语义 → 落 sys_file；写端点纳入审计）。 */
    @PostMapping("/direct-upload-completions")
    @RequirePermission(SystemPermissions.INFRA_FILE_UPLOAD)
    @AuditLog(action = "infra.directUpload.complete", targetType = "file",
            contentParams = {"request.objectKey", "request.fileName", "request.size"})
    public FileView complete(@Valid @RequestBody CompletionRequest request) {
        return fileUseCase.completeDirectUpload(request.objectKey(), request.fileName(),
                request.contentType(), request.size(), request.bizType(), request.bizId(), currentUserId());
    }

    /** 直传准备请求（REQ-20261117-045754 §API：{fileName, contentType, size, bizType?, bizId?}）。 */
    record PreparationRequest(
            @NotBlank @Size(max = 512) String fileName,
            @Size(max = 256) String contentType,
            @NotNull @Positive long size,
            @Size(max = 128) String bizType,
            @Size(max = 128) String bizId) {
    }

    /** 直传完成请求（REQ-20261117-045754 §API：{objectKey, fileName, contentType, size, bizType?, bizId?}）。 */
    record CompletionRequest(
            @NotBlank String objectKey,
            @NotBlank @Size(max = 512) String fileName,
            @Size(max = 256) String contentType,
            @NotNull @Positive long size,
            @Size(max = 128) String bizType,
            @Size(max = 128) String bizId) {
    }

    private static String currentUserId() {
        UserContext context = UserContext.get();
        return context == null ? null : context.userId();
    }
}
