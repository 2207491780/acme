package com.acme.platform.system.interfaces;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.platform.system.api.SystemPermissions;
import com.acme.platform.system.api.dto.AdminNoticeCreate;
import com.acme.platform.system.api.dto.AdminNoticeUpdate;
import com.acme.platform.system.api.dto.AdminNoticeView;
import com.acme.platform.system.application.NoticeUseCase;
import com.acme.platform.system.domain.SysNotice;
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
 * 通知公告接口（REQ-20261114-120102：路径与契约 §API 逐字一致；权限 system:notice:*）。
 */
@RestController
@RequestMapping("/admin/v1/system/notices")
public class NoticeController {

    private final NoticeUseCase noticeUseCase;

    public NoticeController(NoticeUseCase noticeUseCase) {
        this.noticeUseCase = noticeUseCase;
    }

    @GetMapping
    @RequirePermission(SystemPermissions.NOTICE_LIST)
    public PageResult<AdminNoticeView> list(@RequestParam(defaultValue = "1") Integer page,
                                            @RequestParam(defaultValue = "20") Integer size,
                                            @RequestParam(required = false) String keyword) {
        PageResult<SysNotice> result = noticeUseCase.page(new PageQuery(page, size), keyword);
        return new PageResult<>(
                result.records().stream().map(EntityViews::noticeView).toList(), result.total());
    }

    @PostMapping
    @RequirePermission(SystemPermissions.NOTICE_CREATE)
    public AdminNoticeView create(@Valid @RequestBody AdminNoticeCreate request) {
        return EntityViews.noticeView(noticeUseCase.create(request));
    }

    /** 公告详情（归 list 读语义，V7 元数据读取先例）。 */
    @GetMapping("/{noticeId}")
    @RequirePermission(SystemPermissions.NOTICE_LIST)
    public AdminNoticeView get(@PathVariable Long noticeId) {
        return EntityViews.noticeView(noticeUseCase.get(noticeId));
    }

    @PutMapping("/{noticeId}")
    @RequirePermission(SystemPermissions.NOTICE_UPDATE)
    public AdminNoticeView update(@PathVariable Long noticeId,
                                  @Valid @RequestBody AdminNoticeUpdate request) {
        return EntityViews.noticeView(noticeUseCase.update(noticeId, request));
    }

    @PutMapping("/{noticeId}/publish")
    @RequirePermission(SystemPermissions.NOTICE_PUBLISH)
    public AdminNoticeView publish(@PathVariable Long noticeId) {
        return EntityViews.noticeView(noticeUseCase.publish(noticeId));
    }

    /** 下线公告（确认记录⑤：PUBLISHED→OFFLINE 幂等；OFFLINE 可编辑/可删除/可再发布）。
     *  下线与发布同属生命周期操作，共用 system:notice:publish 权限点（TL 裁决⑤）。 */
    @PutMapping("/{noticeId}/offline")
    @RequirePermission(SystemPermissions.NOTICE_PUBLISH)
    public AdminNoticeView offline(@PathVariable Long noticeId) {
        return EntityViews.noticeView(noticeUseCase.offline(noticeId));
    }

    @DeleteMapping("/{noticeId}")
    @RequirePermission(SystemPermissions.NOTICE_DELETE)
    public void delete(@PathVariable Long noticeId) {
        noticeUseCase.delete(noticeId);
    }
}
