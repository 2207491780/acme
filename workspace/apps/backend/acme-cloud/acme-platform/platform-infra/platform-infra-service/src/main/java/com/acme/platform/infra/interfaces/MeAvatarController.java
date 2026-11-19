package com.acme.platform.infra.interfaces;

import com.acme.common.context.UserContext;
import com.acme.platform.infra.application.FileUseCase;
import com.acme.platform.infra.api.dto.FileView;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 本人头像上传接口（REQ-20261119-082531 确认②/TL 裁决：承载于 platform-infra，
 * 认证即可（无 infra:file:upload 权限面）——文件校验（类型/图片语义/配额）仍由
 * FileUseCase 统一承担；avatar_url 的持久化由前端两步流程的第二步
 * PUT /admin/v1/system/me 提交（platform-system 域）。
 * bizType='avatar'、bizId=本人 userId（sys_file 关联口径，保留期清理自然覆盖）。
 */
@RestController
@RequestMapping("/admin/v1/infra/me")
public class MeAvatarController {

    private final FileUseCase fileUseCase;

    public MeAvatarController(FileUseCase fileUseCase) {
        this.fileUseCase = fileUseCase;
    }

    /** 上传本人头像（multipart；返回 FileView.url 供 PUT /me 提交 avatarUrl）。 */
    @PostMapping("/avatar")
    public FileView avatar(@RequestParam("file") @NotNull MultipartFile file) {
        String userId = UserContext.get().userId();
        return fileUseCase.upload(file, "avatar", userId, userId);
    }
}
