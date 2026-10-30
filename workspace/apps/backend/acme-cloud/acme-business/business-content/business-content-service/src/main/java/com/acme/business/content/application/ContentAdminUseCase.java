package com.acme.business.content.application;

import com.acme.business.content.api.BatchAction;
import com.acme.business.content.api.ContentPermissions;
import com.acme.business.content.api.ContentStatus;
import com.acme.business.content.api.dto.AdminContentBatchResultView;
import com.acme.business.content.domain.BizContent;
import com.acme.business.content.infrastructure.client.AuthorizationGateway;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 管理侧写用例（架构设计 2.1）：编排权限判定（QD-05 批量按动作取权限点）与本地事务写（{@link ContentWriter}）；
 * 权限判定为远程调用，保持在事务外（M-05）；其余 B 端读/写端点权限经接口层 @RequirePermission 逐端点判定（6.5）。
 */
@Service
public class ContentAdminUseCase {

    private final AuthorizationGateway authorizationGateway;
    private final ContentWriter contentWriter;

    public ContentAdminUseCase(AuthorizationGateway authorizationGateway, ContentWriter contentWriter) {
        this.authorizationGateway = authorizationGateway;
        this.contentWriter = contentWriter;
    }

    /** 标题行内修改（FR-008）。 */
    public BizContent renameTitle(Long contentId, String title, Long expectedVersion) {
        return contentWriter.rename(contentId, title, expectedVersion);
    }

    /** 状态流转（FR-009）。 */
    public BizContent transitionStatus(Long contentId, ContentStatus targetStatus) {
        return contentWriter.transition(contentId, targetStatus);
    }

    /** 批量下线 / 批量删除（FR-010）：权限点按 action 判定（QD-05），再入事务写。 */
    public AdminContentBatchResultView batch(BatchAction action, List<String> ids) {
        authorizationGateway.requirePermission(action == BatchAction.offline
                ? ContentPermissions.CONTENT_BATCH_OFFLINE
                : ContentPermissions.CONTENT_BATCH_DELETE);
        return contentWriter.batch(action, ids);
    }
}
