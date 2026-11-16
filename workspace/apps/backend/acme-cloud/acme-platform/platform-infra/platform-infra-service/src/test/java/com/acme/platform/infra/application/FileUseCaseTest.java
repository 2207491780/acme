package com.acme.platform.infra.application;

import com.acme.common.context.TenantContext;
import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.common.core.BizException;
import com.acme.framework.storage.config.StorageProperties;
import com.acme.framework.storage.support.ObjectKey;
import com.acme.framework.storage.support.ObjectStorage;
import com.acme.framework.storage.support.StorageObject;
import com.acme.framework.tenant.support.TenantError;
import com.acme.platform.infra.InfraError;
import com.acme.platform.infra.api.dto.DirectUploadPreparationView;
import com.acme.platform.infra.api.dto.FileView;
import com.acme.platform.infra.domain.SysFile;
import com.acme.platform.infra.infrastructure.redis.UploadTicketStore;
import com.acme.platform.infra.infrastructure.repository.SysFileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文件服务用例单元测试（REQ-20261114-071607；REQ-20261117-045754 扩展）：
 * 纯 JUnit + Mockito，无 Spring。覆盖 multipart 上传（白名单/大小/配额三态/图片语义三态/租户段组装）、
 * 直传三段（preparation 发票/预签名、票据校验分支、completion 幂等与校验链）、objectKeyOf 5/6 段兼容。
 */
class FileUseCaseTest {

    private static final String XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String TENANT = "t-1";
    private static final String OPERATOR = "op-1";

    private SysFileRepository repository;
    private ObjectStorage storage;
    private StorageProperties properties;
    private UploadTicketStore ticketStore;
    private FileUseCase useCase;

    @BeforeEach
    void setUp() {
        repository = mock(SysFileRepository.class);
        storage = mock(ObjectStorage.class);
        ticketStore = mock(UploadTicketStore.class);
        properties = new StorageProperties();
        useCase = new FileUseCase(repository, storage, properties, ticketStore);
        TenantContext.bind(new TenantContext(TENANT));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void saveReturnsId() {
        when(repository.save(any(SysFile.class))).thenAnswer(invocation -> {
            SysFile entity = invocation.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", 10001L);
            return entity;
        });
    }

    /** 生成真实可解码 PNG（图片语义用例夹具）。 */
    private static byte[] pngBytes(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0xFF0000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    // ---------- multipart 上传（REQ-20261114-071607 既有语义） ----------

    @Test
    void uploadPersistsObjectAndRecord() {
        saveReturnsId();
        when(storage.url(any(ObjectKey.class))).thenReturn("http://files.test/acme/files/x");
        when(repository.sumActiveSize()).thenReturn(0L);

        FileView view = useCase.upload(new MockMultipartFile("file", "hello.xlsx", XLSX_MIME,
                "xlsx-bytes".getBytes(StandardCharsets.UTF_8)), "req", "42", "op-1");

        assertThat(view.id()).isEqualTo("10001");
        assertThat(view.originalName()).isEqualTo("hello.xlsx");
        assertThat(view.size()).isEqualTo("xlsx-bytes".getBytes(StandardCharsets.UTF_8).length);
        assertThat(view.contentType()).isEqualTo(XLSX_MIME);
        assertThat(view.bizType()).isEqualTo("req");
        assertThat(view.bizId()).isEqualTo("42");
        assertThat(view.url()).startsWith("http://files.test");

        ArgumentCaptor<StorageObject> putCaptor = ArgumentCaptor.forClass(StorageObject.class);
        verify(storage).put(putCaptor.capture());
        assertThat(putCaptor.getValue().size()).isEqualTo(10L);
        assertThat(putCaptor.getValue().key().name()).endsWith(".xlsx");

        ArgumentCaptor<SysFile> saveCaptor = ArgumentCaptor.forClass(SysFile.class);
        verify(repository).save(saveCaptor.capture());
        assertThat(saveCaptor.getValue().getOriginalName()).isEqualTo("hello.xlsx");
        assertThat(saveCaptor.getValue().getUploadedBy()).isEqualTo("op-1");
        assertThat(saveCaptor.getValue().getBucket()).isEqualTo(properties.getBucket());
        assertThat(saveCaptor.getValue().getObjectKey())
                .isEqualTo(putCaptor.getValue().key().path());
    }

    @Test
    void should_assembleTenantSegmentObjectKey_when_uploadWithTenantContext() {
        // 确认②：multipart 与直传同走租户段新格式 files/{tenantCode}/yyyy/MM/dd/uuid.ext
        saveReturnsId();
        when(repository.sumActiveSize()).thenReturn(0L);

        useCase.upload(new MockMultipartFile("file", "a.txt", "text/plain",
                "a".getBytes(StandardCharsets.UTF_8)), null, null, OPERATOR);

        ArgumentCaptor<StorageObject> putCaptor = ArgumentCaptor.forClass(StorageObject.class);
        verify(storage).put(putCaptor.capture());
        assertThat(putCaptor.getValue().key().namespace()).isEqualTo("files/" + TENANT);
        String[] segments = putCaptor.getValue().key().path().split("/");
        assertThat(segments).hasSize(6);
        assertThat(segments[0]).isEqualTo("files");
        assertThat(segments[1]).isEqualTo(TENANT);
        // 日期段与唯一名段形态
        LocalDate.parse(segments[2] + "-" + segments[3] + "-" + segments[4]);
        assertThat(segments[5]).endsWith(".txt");

        ArgumentCaptor<SysFile> saveCaptor = ArgumentCaptor.forClass(SysFile.class);
        verify(repository).save(saveCaptor.capture());
        assertThat(saveCaptor.getValue().getObjectKey()).isEqualTo(putCaptor.getValue().key().path());
    }

    @Test
    void should_rejectUpload_when_tenantContextMissing() {
        TenantContext.clear();

        assertThatThrownBy(() -> useCase.upload(new MockMultipartFile("file", "a.txt", "text/plain",
                "a".getBytes(StandardCharsets.UTF_8)), null, null, null))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(TenantError.TENANT_REQUIRED));

        verify(storage, never()).put(any(StorageObject.class));
    }

    @Test
    void uploadRejectsOversizeWithFileSizeExceededAndNothingPersisted() {
        properties.getUpload().setMaxSize(org.springframework.util.unit.DataSize.ofKilobytes(1));

        assertThatThrownBy(() -> useCase.upload(new MockMultipartFile("file", "big.txt", "text/plain",
                new byte[2 * 1024]), null, null, null))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(InfraError.FILE_SIZE_EXCEEDED));

        verify(storage, never()).put(any(StorageObject.class));
        verify(repository, never()).save(any(SysFile.class));
    }

    @Test
    void uploadRejectsTypeOutsideWhitelistWithFileTypeRejectedAndNothingPersisted() {
        assertThatThrownBy(() -> useCase.upload(new MockMultipartFile("file", "evil.zip", "application/zip",
                "zip-content".getBytes(StandardCharsets.UTF_8)), null, null, null))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(InfraError.FILE_TYPE_REJECTED));

        verify(storage, never()).put(any(StorageObject.class));
        verify(repository, never()).save(any(SysFile.class));
    }

    @Test
    void uploadAcceptsWhitelistedMimeWithoutExtensionMatch() {
        // 双口径：无扩展名但 contentType 命中白名单（pdf）→ 放行
        saveReturnsId();
        when(repository.sumActiveSize()).thenReturn(0L);
        useCase.upload(new MockMultipartFile("file", "noext", "application/pdf",
                "pdf".getBytes(StandardCharsets.UTF_8)), null, null, null);
        verify(storage).put(any(StorageObject.class));
        verify(repository).save(any(SysFile.class));
    }

    // ---------- multipart 配额三态（REQ-20261117-045754 确认③） ----------

    @Test
    void should_throwQuotaExceeded_when_uploadExceedsTenantQuota() {
        // 已用 900B + 本次 200B > 配额 1KB → STORAGE_QUOTA_EXCEEDED，无对象无记录
        properties.getQuota().setDefaultBytes(1024L);
        when(repository.sumActiveSize()).thenReturn(900L);

        assertThatThrownBy(() -> useCase.upload(new MockMultipartFile("file", "a.txt", "text/plain",
                new byte[200]), null, null, null))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(InfraError.STORAGE_QUOTA_EXCEEDED));

        verify(storage, never()).put(any(StorageObject.class));
        verify(repository, never()).save(any(SysFile.class));
    }

    @Test
    void should_passUpload_when_usedPlusIncomingWithinQuota() {
        // 已用 900B + 本次 100B == 配额 1KB（边界含等号）→ 放行
        properties.getQuota().setDefaultBytes(1024L);
        when(repository.sumActiveSize()).thenReturn(900L);
        saveReturnsId();

        useCase.upload(new MockMultipartFile("file", "a.txt", "text/plain",
                new byte[124]), null, null, null);

        verify(storage).put(any(StorageObject.class));
        verify(repository).save(any(SysFile.class));
    }

    @Test
    void should_skipQuotaCheck_when_quotaZeroMeansUnlimited() {
        // 0=不限（确认③）：即便巨大用量也放行
        properties.getQuota().setDefaultBytes(0L);
        when(repository.sumActiveSize()).thenReturn(Long.MAX_VALUE / 2);
        saveReturnsId();

        useCase.upload(new MockMultipartFile("file", "a.txt", "text/plain",
                new byte[1024]), null, null, null);

        verify(storage).put(any(StorageObject.class));
    }

    // ---------- multipart 图片语义三态（确认④） ----------

    @Test
    void should_throwImageInvalid_when_uploadImageUndecodable() {
        saveReturnsId();

        assertThatThrownBy(() -> useCase.upload(new MockMultipartFile("file", "broken.png",
                "image/png", "not-an-image".getBytes(StandardCharsets.UTF_8)), null, null, null))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(InfraError.IMAGE_INVALID));

        verify(storage, never()).put(any(StorageObject.class));
        verify(repository, never()).save(any(SysFile.class));
    }

    @Test
    void should_throwImageInvalid_when_uploadImageExceedsMaxImagePx() throws IOException {
        properties.getUpload().setMaxImagePx(64);
        saveReturnsId();

        assertThatThrownBy(() -> useCase.upload(new MockMultipartFile("file", "huge.png",
                "image/png", pngBytes(5000, 10)), null, null, null))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(InfraError.IMAGE_INVALID));

        verify(storage, never()).put(any(StorageObject.class));
    }

    @Test
    void should_acceptUpload_when_imageDecodableAndWithinMaxImagePx() throws IOException {
        saveReturnsId();
        when(repository.sumActiveSize()).thenReturn(0L);

        useCase.upload(new MockMultipartFile("file", "ok.png", "image/png",
                pngBytes(32, 32)), null, null, null);

        ArgumentCaptor<StorageObject> putCaptor = ArgumentCaptor.forClass(StorageObject.class);
        verify(storage).put(putCaptor.capture());
        assertThat(putCaptor.getValue().contentType()).isEqualTo("image/png");
        verify(repository).save(any(SysFile.class));
    }

    @Test
    void should_skipImageValidation_when_nonImageContentType() {
        // 非 image/* 不触发解码（坏字节也放行，确认④仅约束 image/ 前缀）
        saveReturnsId();
        when(repository.sumActiveSize()).thenReturn(0L);

        useCase.upload(new MockMultipartFile("file", "a.txt", "text/plain",
                "not-an-image".getBytes(StandardCharsets.UTF_8)), null, null, null);

        verify(storage).put(any(StorageObject.class));
    }

    // ---------- 直传 preparation ----------

    @Test
    void should_issueLocalTicket_when_prepareDirectUploadOnLocalType() {
        // 确认①：LOCAL → 一次性票据 PUT 端点（headers 含 Content-Type，ticket 非空，TTL 5min）
        when(ticketStore.create(any(UploadTicketStore.Ticket.class), any(java.time.Duration.class)))
                .thenReturn("ticket-1");
        when(repository.sumActiveSize()).thenReturn(0L);

        DirectUploadPreparationView view = useCase.prepareDirectUpload(
                "hello.txt", "text/plain", 10L, "req", "42", OPERATOR);

        assertThat(view.objectKey()).startsWith("files/" + TENANT + "/").endsWith(".txt");
        assertThat(view.objectKey().split("/")).hasSize(6);
        assertThat(view.method()).isEqualTo("PUT");
        assertThat(view.uploadUrl()).isEqualTo("/admin/v1/infra/files/direct-uploads/ticket-1");
        assertThat(view.headers()).containsEntry("Content-Type", "text/plain");
        assertThat(view.expiresInSeconds()).isEqualTo(300L);
        assertThat(view.ticket()).isEqualTo("ticket-1");

        ArgumentCaptor<UploadTicketStore.Ticket> ticketCaptor =
                ArgumentCaptor.forClass(UploadTicketStore.Ticket.class);
        verify(ticketStore).create(ticketCaptor.capture(), eq(FileUseCase.TICKET_TTL));
        UploadTicketStore.Ticket ticket = ticketCaptor.getValue();
        assertThat(ticket.objectKey()).isEqualTo(view.objectKey());
        assertThat(ticket.fileName()).isEqualTo("hello.txt");
        assertThat(ticket.contentType()).isEqualTo("text/plain");
        assertThat(ticket.size()).isEqualTo(10L);
        assertThat(ticket.bizType()).isEqualTo("req");
        assertThat(ticket.bizId()).isEqualTo("42");
        assertThat(ticket.tenantId()).isEqualTo(TENANT);
        assertThat(ticket.operatorId()).isEqualTo(OPERATOR);
    }

    @Test
    void should_returnPresignedUrl_when_prepareDirectUploadOnMinioType() {
        // 确认①：MINIO/OSS → facade presignUpload（ticket=null，headers 含 Content-Type）
        properties.setType(StorageProperties.Type.MINIO);
        properties.setEndpoint("http://minio.test");
        properties.setAccessKey("ak");
        properties.setSecretKey("sk");
        when(storage.presignUpload(any(ObjectKey.class), eq("image/png"), eq(7L)))
                .thenReturn("https://minio.test/acme/x?X-Amz-Signature=abc");
        when(repository.sumActiveSize()).thenReturn(0L);

        DirectUploadPreparationView view = useCase.prepareDirectUpload(
                "a.png", "image/png", 7L, null, null, OPERATOR);

        assertThat(view.uploadUrl()).isEqualTo("https://minio.test/acme/x?X-Amz-Signature=abc");
        assertThat(view.method()).isEqualTo("PUT");
        assertThat(view.headers()).containsEntry("Content-Type", "image/png");
        assertThat(view.ticket()).isNull();
        assertThat(view.objectKey()).startsWith("files/" + TENANT + "/");
        verify(ticketStore, never()).create(any(UploadTicketStore.Ticket.class), any(java.time.Duration.class));
    }

    @Test
    void should_throwFileTypeRejected_when_prepareDirectUploadBlankOrZeroSize() {
        assertThatThrownBy(() -> useCase.prepareDirectUpload("", "text/plain", 10L, null, null, OPERATOR))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(InfraError.FILE_TYPE_REJECTED));
        assertThatThrownBy(() -> useCase.prepareDirectUpload("a.txt", "text/plain", 0L, null, null, OPERATOR))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(InfraError.FILE_TYPE_REJECTED));
        verify(ticketStore, never()).create(any(UploadTicketStore.Ticket.class), any(java.time.Duration.class));
    }

    @Test
    void should_throwFileSizeExceeded_when_prepareDirectUploadOversize() {
        assertThatThrownBy(() -> useCase.prepareDirectUpload(
                "big.txt", "text/plain", properties.getUpload().getMaxSize().toBytes() + 1, null, null, OPERATOR))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(InfraError.FILE_SIZE_EXCEEDED));
        verify(ticketStore, never()).create(any(UploadTicketStore.Ticket.class), any(java.time.Duration.class));
    }

    @Test
    void should_throwFileTypeRejected_when_prepareDirectUploadOutsideWhitelist() {
        // AC-7：既有 FILE_TYPE_REJECTED 语义不变
        assertThatThrownBy(() -> useCase.prepareDirectUpload(
                "evil.zip", "application/zip", 10L, null, null, OPERATOR))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(InfraError.FILE_TYPE_REJECTED));
        verify(ticketStore, never()).create(any(UploadTicketStore.Ticket.class), any(java.time.Duration.class));
    }

    @Test
    void should_throwQuotaExceeded_when_prepareDirectUploadExceedsQuota() {
        properties.getQuota().setDefaultBytes(1024L);
        when(repository.sumActiveSize()).thenReturn(2000L);

        assertThatThrownBy(() -> useCase.prepareDirectUpload(
                "a.txt", "text/plain", 10L, null, null, OPERATOR))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(InfraError.STORAGE_QUOTA_EXCEEDED));
        verify(ticketStore, never()).create(any(UploadTicketStore.Ticket.class), any(java.time.Duration.class));
    }

    // ---------- 票据消费分支（确认⑥） ----------

    @Test
    void should_throwTicketInvalid_when_ticketMissingOrConsumed() {
        when(ticketStore.take(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.consumeTicket("gone", OPERATOR))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(InfraError.TICKET_INVALID));
    }

    @Test
    void should_throwTicketInvalid_when_ticketBoundToOtherTenant() {
        // 防跨租户替传（确认⑥）：票据租户 ≠ 当前租户
        when(ticketStore.take("t2-ticket")).thenReturn(Optional.of(new UploadTicketStore.Ticket(
                "files/other/2026/11/17/x.bin", "x.bin", "text/plain", 1L,
                null, null, "other-tenant", OPERATOR)));

        assertThatThrownBy(() -> useCase.consumeTicket("t2-ticket", OPERATOR))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(InfraError.TICKET_INVALID));
    }

    @Test
    void should_throwTicketInvalid_when_ticketBoundToOtherOperator() {
        when(ticketStore.take("t1-ticket")).thenReturn(Optional.of(new UploadTicketStore.Ticket(
                "files/" + TENANT + "/2026/11/17/x.bin", "x.bin", "text/plain", 1L,
                null, null, TENANT, "someone-else")));

        assertThatThrownBy(() -> useCase.consumeTicket("t1-ticket", OPERATOR))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(InfraError.TICKET_INVALID));
    }

    @Test
    void should_returnTicketPayload_when_ticketMatchesTenantAndOperator() {
        UploadTicketStore.Ticket expected = new UploadTicketStore.Ticket(
                "files/" + TENANT + "/2026/11/17/x.bin", "x.bin", "text/plain", 1L,
                "req", "42", TENANT, OPERATOR);
        when(ticketStore.take("ok-ticket")).thenReturn(Optional.of(expected));

        assertThat(useCase.consumeTicket("ok-ticket", OPERATOR)).isSameAs(expected);
    }

    @Test
    void should_throwTicketInvalid_when_putContentTypeMismatch() {
        UploadTicketStore.Ticket ticket = new UploadTicketStore.Ticket(
                "files/" + TENANT + "/2026/11/17/x.bin", "x.bin", "text/plain", 1L,
                null, null, TENANT, OPERATOR);

        assertThatThrownBy(() -> useCase.putDirectUpload(ticket,
                new ByteArrayInputStream("x".getBytes()), 1L, "application/zip"))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(InfraError.TICKET_INVALID));

        verify(storage, never()).put(any(StorageObject.class));
    }

    @Test
    void should_writeObjectAtTicketObjectKey_when_putDirectUploadValid() {
        UploadTicketStore.Ticket ticket = new UploadTicketStore.Ticket(
                "files/" + TENANT + "/2026/11/17/x.bin", "x.bin", "text/plain", 1L,
                null, null, TENANT, OPERATOR);
        byte[] payload = "raw-bytes".getBytes(StandardCharsets.UTF_8);

        useCase.putDirectUpload(ticket, new ByteArrayInputStream(payload), payload.length, "text/plain");

        ArgumentCaptor<StorageObject> putCaptor = ArgumentCaptor.forClass(StorageObject.class);
        verify(storage).put(putCaptor.capture());
        assertThat(putCaptor.getValue().key().path()).isEqualTo(ticket.objectKey());
        assertThat(putCaptor.getValue().size()).isEqualTo(payload.length);
        assertThat(putCaptor.getValue().contentType()).isEqualTo("text/plain");
    }

    // ---------- 直传 completion ----------

    @Test
    void should_returnExistingViewWithoutRewrite_when_completionObjectKeyAlreadyPresent() {
        // 确认⑥幂等：同 objectKey 未删行命中直接返回既有 view（不重复落库、不再访问对象）
        SysFile existing = new SysFile("acme", "files/" + TENANT + "/2026/11/17/dup.bin",
                "dup.bin", 3L, "text/plain", "req", "42");
        when(repository.findActiveByObjectKey("files/" + TENANT + "/2026/11/17/dup.bin"))
                .thenReturn(Optional.of(existing));

        FileView view = useCase.completeDirectUpload("files/" + TENANT + "/2026/11/17/dup.bin",
                "dup.bin", "text/plain", 3L, "req", "42", OPERATOR);

        assertThat(view.originalName()).isEqualTo("dup.bin");
        assertThat(view.size()).isEqualTo(3L);
        assertThat(view.bizType()).isEqualTo("req");
        verify(storage, never()).exists(any(ObjectKey.class));
        verify(storage, never()).put(any(StorageObject.class));
        verify(repository, never()).save(any(SysFile.class));
    }

    @Test
    void should_throwTenantMismatch_when_completionObjectKeyBoundToOtherTenant() {
        assertThatThrownBy(() -> useCase.completeDirectUpload(
                "files/other-tenant/2026/11/17/x.bin", "x.bin", "text/plain", 1L, null, null, OPERATOR))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(TenantError.TENANT_MISMATCH));
        verify(repository, never()).findActiveByObjectKey(anyString());
    }

    @Test
    void should_throwTenantMismatch_when_completionObjectKeyIsLegacyFiveSegments() {
        // 5 段存量键无租户段，直传 completion 恒拒绝（直传键恒 6 段新格式）
        assertThatThrownBy(() -> useCase.completeDirectUpload(
                "files/2026/11/17/x.bin", "x.bin", "text/plain", 1L, null, null, OPERATOR))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(TenantError.TENANT_MISMATCH));
    }

    @Test
    void should_throwObjectNotFound_when_completionObjectMissingInStorage() {
        when(storage.exists(any(ObjectKey.class))).thenReturn(false);

        assertThatThrownBy(() -> useCase.completeDirectUpload(
                "files/" + TENANT + "/2026/11/17/absent.bin", "absent.bin", "text/plain", 1L,
                null, null, OPERATOR))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(InfraError.STORAGE_OBJECT_MISSING));
        verify(repository, never()).save(any(SysFile.class));
    }

    @Test
    void should_throwSizeMismatch_when_completionSizeDiffersFromStoredBytes() {
        // 容差 0（确认⑥）：字节数与请求 size 不一致 → SIZE_MISMATCH
        when(storage.exists(any(ObjectKey.class))).thenReturn(true);
        byte[] payload = "12345".getBytes(StandardCharsets.UTF_8);
        when(storage.get(any(ObjectKey.class)))
                .thenReturn(new ByteArrayInputStream(payload));

        assertThatThrownBy(() -> useCase.completeDirectUpload(
                "files/" + TENANT + "/2026/11/17/x.bin", "x.bin", "text/plain", 4L, null, null, OPERATOR))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(InfraError.SIZE_MISMATCH));
        verify(repository, never()).save(any(SysFile.class));
    }

    @Test
    void should_persistRecordAndReturnView_when_completionValidObject() {
        saveReturnsId();
        when(storage.exists(any(ObjectKey.class))).thenReturn(true);
        byte[] payload = "abc".getBytes(StandardCharsets.UTF_8);
        when(storage.get(any(ObjectKey.class)))
                .thenReturn(new ByteArrayInputStream(payload));
        when(storage.url(any(ObjectKey.class))).thenReturn("http://files.test/acme/files/x");

        FileView view = useCase.completeDirectUpload(
                "files/" + TENANT + "/2026/11/17/x.txt", "x.txt", "text/plain", 3L, "req", "42", OPERATOR);

        assertThat(view.id()).isEqualTo("10001");
        assertThat(view.originalName()).isEqualTo("x.txt");
        assertThat(view.size()).isEqualTo(3L);
        ArgumentCaptor<SysFile> saveCaptor = ArgumentCaptor.forClass(SysFile.class);
        verify(repository).save(saveCaptor.capture());
        assertThat(saveCaptor.getValue().getObjectKey()).isEqualTo("files/" + TENANT + "/2026/11/17/x.txt");
        assertThat(saveCaptor.getValue().getBucket()).isEqualTo(properties.getBucket());
        assertThat(saveCaptor.getValue().getUploadedBy()).isEqualTo(OPERATOR);
        // tenant_id 由 Flex TenantFactory 落库时注入（单元测试 mock 仓储不触发，集成测试覆盖）
    }

    @Test
    void should_throwImageInvalid_when_completionImageUndecodable() {
        when(storage.exists(any(ObjectKey.class))).thenReturn(true);
        when(storage.get(any(ObjectKey.class)))
                .thenReturn(new ByteArrayInputStream("not-an-image".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> useCase.completeDirectUpload(
                "files/" + TENANT + "/2026/11/17/broken.png", "broken.png", "image/png",
                "not-an-image".getBytes(StandardCharsets.UTF_8).length, null, null, OPERATOR))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(InfraError.IMAGE_INVALID));
        verify(repository, never()).save(any(SysFile.class));
    }

    @Test
    void should_throwImageInvalid_when_completionImageExceedsMaxImagePx() throws IOException {
        properties.getUpload().setMaxImagePx(64);
        when(storage.exists(any(ObjectKey.class))).thenReturn(true);
        byte[] huge = pngBytes(5000, 10);
        when(storage.get(any(ObjectKey.class))).thenReturn(new ByteArrayInputStream(huge));

        assertThatThrownBy(() -> useCase.completeDirectUpload(
                "files/" + TENANT + "/2026/11/17/huge.png", "huge.png", "image/png",
                huge.length, null, null, OPERATOR))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(InfraError.IMAGE_INVALID));
        verify(repository, never()).save(any(SysFile.class));
    }

    @Test
    void should_acceptCompletion_when_imageDecodableAndWithinMaxImagePx() throws IOException {
        saveReturnsId();
        when(storage.exists(any(ObjectKey.class))).thenReturn(true);
        byte[] ok = pngBytes(32, 32);
        when(storage.get(any(ObjectKey.class))).thenReturn(new ByteArrayInputStream(ok));

        useCase.completeDirectUpload("files/" + TENANT + "/2026/11/17/ok.png", "ok.png",
                "image/png", ok.length, null, null, OPERATOR);

        verify(repository).save(any(SysFile.class));
    }

    // ---------- objectKeyOf 5/6 段兼容（确认②） ----------

    @Test
    void should_parseSixSegmentObjectKey_when_tenantSegmentFormat() {
        // 新格式 6 段可逆解析（path() 还原一致）
        ObjectKey key = FileUseCase.objectKeyOf("files/t-9/2026/11/17/abc.png");
        assertThat(key.path()).isEqualTo("files/t-9/2026/11/17/abc.png");
        assertThat(key.namespace()).isEqualTo("files/t-9");
    }

    @Test
    void should_parseFiveSegmentLegacyObjectKey_when_existingRow() {
        // AC-6：存量 5 段行兼容解析（元数据/下载/清理不回归）
        ObjectKey key = FileUseCase.objectKeyOf("files/2026/11/14/abc.txt");
        assertThat(key.path()).isEqualTo("files/2026/11/14/abc.txt");
        assertThat(key.namespace()).isEqualTo("files");
    }

    @Test
    void should_throwIllegalState_when_objectKeyShapeUnknown() {
        assertThatThrownBy(() -> FileUseCase.objectKeyOf("files/2026/11/abc.txt"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> FileUseCase.objectKeyOf("files/t-1/2026/11/17/extra/abc.txt"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> FileUseCase.objectKeyOf(null))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(InfraError.FILE_NOT_FOUND));
    }

    // ---------- 既有面（REQ-20261114-071607） ----------

    @Test
    void getMissingFileFailsWithFileNotFound() {
        when(repository.findById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.get(999L))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(InfraError.FILE_NOT_FOUND));
    }

    @Test
    void deleteRemovesObjectAndSoftDeletesRecord() {
        SysFile file = new SysFile("acme", "files/2026/11/14/abc.txt", "a.txt", 1L,
                "text/plain", null, null);
        when(repository.findById(7L)).thenReturn(Optional.of(file));

        useCase.delete(7L);

        verify(storage).remove(FileUseCase.objectKeyOf("files/2026/11/14/abc.txt"));
        verify(repository).deleteById(7L);
    }

    @Test
    void deleteIsIdempotentWhenRecordAlreadyDeleted() {
        // AC-4 用例面：已软删（过滤面查空）/不存在 → 幂等成功，无对象操作
        when(repository.findById(7L)).thenReturn(Optional.empty());

        useCase.delete(7L);

        verify(storage, never()).remove(any(ObjectKey.class));
        verify(repository, never()).deleteById(any(Long.class));
    }

    @Test
    void pageDelegatesToRepository() {
        when(repository.findAll(any(com.mybatisflex.core.query.QueryWrapper.class), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new PageResult<>(List.of(), 0L));

        PageResult<SysFile> result = useCase.page(new PageQuery(1, 20), "req", "42", "关键字");

        assertThat(result.total()).isZero();
        verify(repository).findAll(any(com.mybatisflex.core.query.QueryWrapper.class), org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(20));
    }
}
