package com.acme.platform.infra.application;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.common.core.BizException;
import com.acme.framework.storage.config.StorageProperties;
import com.acme.framework.storage.support.ObjectKey;
import com.acme.framework.storage.support.ObjectStorage;
import com.acme.framework.storage.support.StorageObject;
import com.acme.platform.infra.InfraError;
import com.acme.platform.infra.api.dto.FileView;
import com.acme.platform.infra.domain.SysFile;
import com.acme.platform.infra.infrastructure.repository.SysFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文件服务用例单元测试（REQ-20261114-071607；纯 JUnit + Mockito，无 Spring）：
 * 上传白名单/大小上限拒绝语义（AC-2 业务侧）、落对象 + 落记录链路（AC-1 用例面）、
 * FILE_NOT_FOUND、删除幂等（AC-4 用例面）。
 */
class FileUseCaseTest {

    private static final String XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private SysFileRepository repository;
    private ObjectStorage storage;
    private StorageProperties properties;
    private FileUseCase useCase;

    @BeforeEach
    void setUp() {
        repository = mock(SysFileRepository.class);
        storage = mock(ObjectStorage.class);
        properties = new StorageProperties();
        useCase = new FileUseCase(repository, storage, properties);
    }

    private void saveReturnsId() {
        when(repository.save(any(SysFile.class))).thenAnswer(invocation -> {
            SysFile entity = invocation.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(entity, "id", 10001L);
            return entity;
        });
    }

    @Test
    void uploadPersistsObjectAndRecord() {
        saveReturnsId();
        when(storage.url(any(ObjectKey.class))).thenReturn("http://files.test/acme/files/x");

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
        assertThat(putCaptor.getValue().key().namespace()).isEqualTo(FileUseCase.NAMESPACE);
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
        useCase.upload(new MockMultipartFile("file", "noext", "application/pdf",
                "pdf".getBytes(StandardCharsets.UTF_8)), null, null, null);
        verify(storage).put(any(StorageObject.class));
        verify(repository).save(any(SysFile.class));
    }

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
