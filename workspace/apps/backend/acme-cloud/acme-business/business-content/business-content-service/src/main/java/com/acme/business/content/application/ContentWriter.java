package com.acme.business.content.application;

import com.acme.business.content.ContentError;
import com.acme.business.content.api.BatchAction;
import com.acme.business.content.api.ContentErrorCodes;
import com.acme.business.content.api.ContentStatus;
import com.acme.business.content.api.dto.AdminBatchFailureView;
import com.acme.business.content.api.dto.AdminContentBatchResultView;
import com.acme.business.content.domain.BizContent;
import com.acme.business.content.infrastructure.repository.BizContentRepository;
import com.acme.common.core.BizException;
import com.acme.framework.web.support.WebError;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内容写入器（应用层事务边界，10.2.1）：标题 / 状态 / 批量动作的本地事务写；
 * 事务内仅本服务库操作（M-05），权限判定在 {@link ContentAdminUseCase} 调用前完成。
 */
@Service
public class ContentWriter {

    private final BizContentRepository repository;

    public ContentWriter(BizContentRepository repository) {
        this.repository = repository;
    }

    /**
     * 标题行内修改（FR-008）：BR-003 非空（领域层 rename）；
     * expectedVersion 可选乐观锁校验（QD-04：携带则校验，冲突 SYS-01-0003；缺失后写覆盖）。
     * 同标题重复提交 = 同结果（自然幂等，10.3.1）。
     */
    @Transactional
    public BizContent rename(Long id, String title, Long expectedVersion) {
        BizContent content = requireLive(id);
        if (expectedVersion != null && content.getVersion() != null
                && !expectedVersion.equals(content.getVersion())) {
            throw new BizException(WebError.CONCURRENCY_CONFLICT);
        }
        content.rename(title);
        return repository.save(content);
    }

    /**
     * 状态流转（FR-009）：同态迁移幂等成功（10.3.1，不判非法）；跨态走 BR-013 状态机（领域层单点 M-08）。
     */
    @Transactional
    public BizContent transition(Long id, ContentStatus target) {
        BizContent content = requireLive(id);
        if (content.getStatus() == target) {
            return content;
        }
        content.transitTo(target);
        return repository.save(content);
    }

    /**
     * 批量动作（FR-010）：逐条结果、不整批回滚（逐条分类、只对可达行执行，事务内无中途失败分支）。
     * 统一口径（架构设计 6.2）：从未存在 → 失败明细；已删 → 幂等成功 no-op；存在 → 执行动作。
     * offline 无条件置 draft（BR-004 / QD-06）；delete 软删（BR-015，逐条 remove 禁 bulk）。
     * ids 归一去重（trim + 解析后 id 双层；total 按去重后条数）。
     */
    @Transactional
    public AdminContentBatchResultView batch(BatchAction action, List<String> rawIds) {
        Set<String> unparsable = new LinkedHashSet<>();
        Map<Long, String> rawByParsed = new LinkedHashMap<>();
        for (String raw : rawIds) {
            String normalized = raw == null ? "" : raw.trim();
            Long id = tryParse(normalized);
            if (id == null) {
                unparsable.add(normalized);
            } else {
                rawByParsed.putIfAbsent(id, normalized);
            }
        }

        List<String> succeeded = new ArrayList<>();
        List<AdminBatchFailureView> failures = new ArrayList<>();
        for (String raw : unparsable) {
            failures.add(notFound(raw));
        }

        Map<Long, Boolean> deletedById = new LinkedHashMap<>();
        if (!rawByParsed.isEmpty()) {
            for (BizContentRepository.ContentRowState state : repository.findRowStatesIncludingDeleted(rawByParsed.keySet())) {
                deletedById.put(state.getId(), Boolean.TRUE.equals(state.getDeleted()));
            }
        }

        List<Long> liveIds = new ArrayList<>();
        for (Map.Entry<Long, String> entry : rawByParsed.entrySet()) {
            Boolean deleted = deletedById.get(entry.getKey());
            if (deleted == null) {
                failures.add(notFound(entry.getValue()));
            } else if (deleted) {
                // 已删行：幂等成功 no-op（重复提交结果集与首次一致，架构设计 6.2）
                succeeded.add(entry.getValue());
            } else {
                liveIds.add(entry.getKey());
            }
        }

        if (!liveIds.isEmpty()) {
            Map<Long, BizContent> liveRows = new LinkedHashMap<>();
            for (BizContent content : repository.findAllById(liveIds)) {
                liveRows.put(content.getId(), content);
            }
            for (Long id : liveIds) {
                String raw = rawByParsed.get(id);
                BizContent content = liveRows.get(id);
                if (content == null) {
                    failures.add(notFound(raw));
                    continue;
                }
                if (action == BatchAction.offline) {
                    content.forceDraft();
                    succeeded.add(raw);
                } else {
                    // @SoftDelete 改写为置标记（BR-015）；逐条 remove（框架边界禁 bulk delete，架构设计 3.2）
                    repository.delete(content);
                    succeeded.add(raw);
                }
            }
            if (action == BatchAction.offline) {
                repository.saveAll(liveRows.values());
            }
        }
        return new AdminContentBatchResultView((long) (unparsable.size() + rawByParsed.size()), succeeded, failures);
    }

    private BizContent requireLive(Long id) {
        return repository.findById(id).orElseThrow(() -> new BizException(ContentError.CONTENT_NOT_FOUND));
    }

    private static AdminBatchFailureView notFound(String raw) {
        return new AdminBatchFailureView(raw, ContentErrorCodes.CONTENT_NOT_FOUND,
                ContentError.CONTENT_NOT_FOUND.defaultMessage());
    }

    /** 雪花字符串解析；解析失败按「从未存在」计失败明细（架构设计 6.2）。 */
    private static Long tryParse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
