package com.acme.platform.infra.application;

import com.acme.common.context.TenantContext;
import com.acme.framework.storage.config.StorageProperties;
import com.acme.framework.storage.support.ObjectStorage;
import com.acme.platform.infra.InfraError;
import com.acme.platform.infra.infrastructure.redis.UploadTicketStore;
import com.acme.platform.infra.infrastructure.repository.SysFileRepository;
import com.acme.platform.system.api.ContractResult;
import com.acme.platform.system.api.TenantQuotaClient;
import com.acme.platform.system.api.TenantQuotaView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REQ-20261122-150802（TENANT-4）AC-3 闭环腿：infra 侧配额超限黑盒闭环（TL AMB-3 裁决交付）。
 *
 * <p>given 配额上界=配额客户端解析值（{@code TenantQuotaClient.resolveQuota} 桩返回解析后有效值；
 * 三级覆盖链行为不在本用例展开——system 侧
 * {@code REQ20261122150802TenantLifecycleQuotaIntegrationTest} 已盖三级链与清除回落）
 * when 上传使 sumActiveSize+incoming 超过该值 then {@code STORAGE_QUOTA_EXCEEDED} 业务码拒绝
 * （统一响应体口径=业务码，unit 缝照范本断言 {@code BizException.errorCode()}，信封翻译在 web 层）。</p>
 *
 * <p>覆盖两条（TL 建议①②）：</p>
 * <ul>
 *   <li>①resolve 返回小配额（1024B）→ sumActive 900B+incoming 200B 超限 → 拒绝，无对象无记录；</li>
 *   <li>②resolve 返回 0（=不限，契约③语义）→ 同量级上传不拒 → 放行落库。</li>
 * </ul>
 *
 * <p>装配照抄范本 {@link FileUseCaseTest}（plain JUnit + Mockito，无 Spring；StorageProperties
 * 真实例、TenantContext 绑定/清理、saveReturnsId 桩）。配额客户端=REQ-150802 交付的 Feign 契约
 * {@code com.acme.platform.system.api.TenantQuotaClient}（/internal/v1/tenants/quota），
 * Mockito 桩返回成功信封 {@code ContractResult.ok(new TenantQuotaView(bytes, source))}。</p>
 *
 * <p>【AMB 假设（TL 裁决锚点；失败即交 TL，不读实现自修）】</p>
 * <ul>
 *   <li>AMB-1 FileUseCase 构造第 5 参=TenantQuotaClient（构造注入；编译裁决即验证——若签名漂移
 *       按 TL 硬约束 AMB-3-b 停止上报）。</li>
 *   <li>AMB-2 checkQuota 以 resolveQuota(当前租户上下文码) 的 quotaStorageBytes 为拒绝上界；
 *       失败/未知租户信封的消费方降级口径不在本用例展开（契约注「由调用方承担」）。</li>
 *   <li>AMB-3 resolve 桩的 quotaSource 不参与断言（来源层级正确性归解析链，system 侧已盖）。</li>
 * </ul>
 */
class REQ20261122150802InfraStorageQuotaClosedLoopTest {

    private static final String TENANT = "t-1";
    private static final String OPERATOR = "op-1";

    private SysFileRepository repository;
    private ObjectStorage storage;
    private StorageProperties properties;
    private UploadTicketStore ticketStore;
    private TenantQuotaClient tenantQuotaClient;
    private FileUseCase useCase;

    @BeforeEach
    void setUp() {
        repository = mock(SysFileRepository.class);
        storage = mock(ObjectStorage.class);
        ticketStore = mock(UploadTicketStore.class);
        properties = new StorageProperties();
        tenantQuotaClient = mock(TenantQuotaClient.class);
        // AMB-1：第 5 参=配额客户端（编译裁决即验证；签名漂移=AMB-3-b 停止上报）
        useCase = new FileUseCase(repository, storage, properties, ticketStore, tenantQuotaClient);
        TenantContext.bind(new TenantContext(TENANT));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void saveReturnsId() {
        when(repository.save(any(com.acme.platform.infra.domain.SysFile.class))).thenAnswer(invocation -> {
            com.acme.platform.infra.domain.SysFile entity = invocation.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", 20001L);
            return entity;
        });
    }

    /** resolve 桩：返回成功信封携解析值（AMB-2：值即拒绝上界；AMB-3：source 不参与断言）。 */
    private void stubResolvedQuota(long quotaStorageBytes) {
        when(tenantQuotaClient.resolveQuota(TENANT))
                .thenReturn(new ContractResult<>(Boolean.TRUE, "0", null,
                        new TenantQuotaView(quotaStorageBytes, "TENANT")));
    }

    // ---------- ①resolve 返回小配额 → 超限拒绝（STORAGE_QUOTA_EXCEEDED） ----------

    @Test
    void should_reject_upload_with_storage_quota_exceeded_when_resolved_quota_smaller_than_sum_plus_incoming() {
        // given：解析值=1024B（租户覆盖臂演示值），已用 900B，本次 200B → 1100B>1024B 超限
        stubResolvedQuota(1024L);
        when(repository.sumActiveSize()).thenReturn(900L);

        assertThatThrownBy(() -> useCase.upload(new MockMultipartFile("file", "a.txt", "text/plain",
                new byte[200]), null, null, OPERATOR))
                .isInstanceOfSatisfying(com.acme.common.core.BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(InfraError.STORAGE_QUOTA_EXCEEDED));

        // 拒绝语义：无对象写、无记录落库（近似护栏语义保留）
        verify(storage, never()).put(any(com.acme.framework.storage.support.StorageObject.class));
        verify(repository, never()).save(any(com.acme.platform.infra.domain.SysFile.class));
    }

    // ---------- ②resolve 返回 0（=不限） → 同量上传不拒（契约③ 0=不限语义保留） ----------

    @Test
    void should_accept_upload_when_resolved_quota_zero_means_unlimited() {
        // given：解析值=0（0=不限），已用巨大用量，本次 1KB → 放行
        stubResolvedQuota(0L);
        when(repository.sumActiveSize()).thenReturn(Long.MAX_VALUE / 2);
        saveReturnsId();

        useCase.upload(new MockMultipartFile("file", "a.txt", "text/plain",
                new byte[1024]), null, null, OPERATOR);

        verify(storage).put(any(com.acme.framework.storage.support.StorageObject.class));
        verify(repository).save(any(com.acme.platform.infra.domain.SysFile.class));
    }
}
