package com.acme.platform.infra.application;

import com.acme.framework.tenant.support.IgnoreTenant;
import com.acme.framework.storage.config.StorageProperties;
import com.acme.framework.storage.support.ObjectKey;
import com.acme.framework.storage.support.ObjectStorage;
import com.acme.platform.infra.domain.SysFile;
import com.acme.platform.infra.infrastructure.repository.SysFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件保留期清理用例（REQ-20261114-071607 示例任务 file-retention-cleanup 业务体）：
 * 清理软删超期（{@code acme.storage.upload.retention-days}，按 updated_at 判定）的 sys_file
 * 及其对象（物理清理 + storage.remove，AC-15）；未超期（含未软删）行不受影响。
 * 平台级跨租户语义（调度无租户语境）：经 {@code @IgnoreTenant} 显式声明旁路（12.4.2 平台管理场景）。
 */
@Service
public class FileRetentionCleanupUseCase {

    private static final Logger log = LoggerFactory.getLogger(FileRetentionCleanupUseCase.class);

    private final SysFileRepository repository;
    private final ObjectStorage storage;
    private final StorageProperties storageProperties;

    public FileRetentionCleanupUseCase(SysFileRepository repository, ObjectStorage storage,
                                       StorageProperties storageProperties) {
        this.repository = repository;
        this.storage = storage;
        this.storageProperties = storageProperties;
    }

    /** 清理软删超期记录及其对象（返回清理条数）。 */
    @IgnoreTenant("infra.file-retention-cleanup")
    public int cleanupExpired() {
        Instant cutoff = Instant.now()
                .minus(Duration.ofDays(storageProperties.getUpload().getRetentionDays()));
        List<SysFile> expired = repository.findSoftDeletedExpiredBefore(cutoff);
        if (expired.isEmpty()) {
            return 0;
        }
        List<Long> removedIds = new ArrayList<>(expired.size());
        for (SysFile file : expired) {
            // 对象先删（幂等）；键解析失败的异常形态记日志跳过，不阻断批量清理
            try {
                ObjectKey key = FileUseCase.objectKeyOf(file.getObjectKey());
                storage.remove(key);
            } catch (RuntimeException ex) {
                log.warn("软删超期对象键解析失败，跳过对象移除: {} ({})", file.getObjectKey(), ex.getMessage());
            }
            removedIds.add(file.getId());
        }
        repository.forceDeleteByIds(removedIds);
        return removedIds.size();
    }
}
