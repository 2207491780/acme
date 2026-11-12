package com.acme.business.content.application;

import com.acme.business.content.api.BatchAction;
import com.acme.business.content.api.ContentPermissions;
import com.acme.business.content.api.dto.AdminContentBatchResultView;
import com.acme.business.content.infrastructure.client.AuthorizationGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理侧写用例单测（16.1）：批量权限点按动作判定（QD-05）+ 事务外权限编排（M-05 口径）。
 */
class ContentAdminUseCaseTest {

    private AuthorizationGateway authorizationGateway;
    private ContentWriter contentWriter;
    private ContentAdminUseCase contentAdminUseCase;

    @BeforeEach
    void setUp() {
        authorizationGateway = mock(AuthorizationGateway.class);
        contentWriter = mock(ContentWriter.class);
        contentAdminUseCase = new ContentAdminUseCase(authorizationGateway, contentWriter);
    }

    @Test
    void batchOfflineChecksOfflinePermission() {
        AdminContentBatchResultView stub = new AdminContentBatchResultView(0L, List.of(), List.of());
        when(contentWriter.batch(any(), anyList())).thenReturn(stub);
        AdminContentBatchResultView result = contentAdminUseCase.batch(BatchAction.offline, List.of("1"));
        verify(authorizationGateway).requirePermission(ContentPermissions.CONTENT_BATCH_OFFLINE);
        assertThat(result.total()).isZero();
    }

    @Test
    void batchDeleteChecksDeletePermission() {
        AdminContentBatchResultView stub = new AdminContentBatchResultView(0L, List.of(), List.of());
        when(contentWriter.batch(any(), anyList())).thenReturn(stub);
        contentAdminUseCase.batch(BatchAction.delete, List.of("1"));
        verify(authorizationGateway).requirePermission(ContentPermissions.CONTENT_BATCH_DELETE);
    }
}
