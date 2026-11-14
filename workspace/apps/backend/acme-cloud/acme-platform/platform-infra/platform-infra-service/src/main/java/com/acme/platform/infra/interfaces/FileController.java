package com.acme.platform.infra.interfaces;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.common.context.UserContext;
import com.acme.platform.infra.api.dto.FileView;
import com.acme.platform.infra.application.FileUseCase;
import com.acme.platform.infra.domain.SysFile;
import com.acme.platform.infra.security.RequirePermission;
import com.acme.platform.system.api.SystemPermissions;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 文件服务 B 端接口（REQ-20261114-071607 §API；权限点 infra:file:*，接口级权限生效）：
 * 上传（multipart）/ 分页 / 元数据 / 流式下载（Content-Disposition 原名）/ 幂等软删。
 * 统一响应体 Result 由 framework-web 出参包装（二进制/流式出参豁免，信封不破坏下载语义）。
 */
@RestController
@RequestMapping("/admin/v1/infra/files")
public class FileController {

    private final FileUseCase fileUseCase;

    public FileController(FileUseCase fileUseCase) {
        this.fileUseCase = fileUseCase;
    }

    /** 上传（multipart：file + 可选 bizType/bizId；白名单 + 大小上限，AC-1/AC-2）。 */
    @PostMapping
    @RequirePermission(SystemPermissions.INFRA_FILE_UPLOAD)
    public FileView upload(@RequestParam("file") MultipartFile file,
                           @RequestParam(required = false) String bizType,
                           @RequestParam(required = false) String bizId) {
        return fileUseCase.upload(file, bizType, bizId, currentUserId());
    }

    /** 分页（page/size + 可选 bizType/bizId/keyword 原名模糊）。 */
    @GetMapping
    @RequirePermission(SystemPermissions.INFRA_FILE_LIST)
    public PageResult<FileView> page(
            @RequestParam(defaultValue = "1") @Min(1) Integer page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) Integer size,
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) String bizId,
            @RequestParam(required = false) String keyword) {
        PageResult<SysFile> result = fileUseCase.page(new PageQuery(page, size), bizType, bizId, keyword);
        List<FileView> records = result.records().stream().map(fileUseCase::view).toList();
        return new PageResult<>(records, result.total());
    }

    /** 元数据（不存在 FILE_NOT_FOUND）。 */
    @GetMapping("/{id}")
    @RequirePermission(SystemPermissions.INFRA_FILE_LIST)
    public FileView metadata(@PathVariable Long id) {
        return fileUseCase.view(fileUseCase.get(id));
    }

    /** 流式下载（字节与原件一致 + Content-Disposition 原名，AC-3；Resource 出参豁免 Result 信封）。 */
    @GetMapping("/{id}/content")
    @RequirePermission(SystemPermissions.INFRA_FILE_DOWNLOAD)
    public ResponseEntity<InputStreamResource> content(@PathVariable Long id) {
        FileUseCase.FileContent content = fileUseCase.openContent(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition(content.file().getOriginalName()))
                .header(HttpHeaders.CONTENT_TYPE, content.file().getContentType() == null
                        ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                        : content.file().getContentType())
                .contentLength(content.file().getSize() == null ? -1L : content.file().getSize())
                .body(new InputStreamResource(content.stream()));
    }

    /**
     * Content-Disposition 原名口径（REQ-20261114-071607 裁决⑦）：ASCII 原名直用
     * {@code filename="..."}；非 ASCII 走 RFC 5987 {@code filename*=UTF-8''...}。
     */
    private static String disposition(String name) {
        if (name == null || name.isBlank()) {
            return "attachment";
        }
        boolean ascii = name.chars().allMatch(c -> c >= 0x20 && c < 0x7F);
        if (ascii) {
            return "attachment; filename=\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename*=UTF-8''" + encoded;
    }

    /** 幂等软删（已删/对象不存在均成功，AC-4；返回删除记录 id 以统一 Result 信封）。 */
    @DeleteMapping("/{id}")
    @RequirePermission(SystemPermissions.INFRA_FILE_DELETE)
    public Long delete(@PathVariable Long id) {
        fileUseCase.delete(id);
        return id;
    }

    private static String currentUserId() {
        UserContext context = UserContext.get();
        return context == null ? null : context.userId();
    }
}
