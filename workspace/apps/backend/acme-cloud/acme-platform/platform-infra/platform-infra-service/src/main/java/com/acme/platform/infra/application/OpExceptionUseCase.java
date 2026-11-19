package com.acme.platform.infra.application;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.common.context.TenantContext;
import com.acme.common.core.BizException;
import com.acme.framework.excel.config.ExcelProperties;
import com.acme.framework.web.event.OpExceptionCapturedEvent;
import com.acme.platform.infra.InfraError;
import com.acme.platform.infra.domain.OpException;
import com.acme.platform.infra.infrastructure.redis.OpExceptionDedupStore;
import com.acme.platform.infra.infrastructure.repository.OpExceptionRepository;
import com.mybatisflex.core.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * 运行异常日志用例（REQ-20261117-061017 确认①④）：
 * <ul>
 *   <li>收集：framework-web {@link OpExceptionCapturedEvent} 异步落库（{@link OpExceptionRecorder}
 *       对齐 LoginLogRecorder 异步口径）；去重键 service+exceptionType+path 60s 一次（Redis SETNX TTL，
 *       防异常风暴）；落库租户以事件 payload 为事实源（缺失归一保留值 system，AuditIngestUseCase 同型），
 *       经 TenantContext 绑定驱动租户列注入并在作用域结束恢复（事务须在绑定作用域内开启）。</li>
 *   <li>查询/详情/导出：本租户自动过滤（12.4.1），occurred_at 倒序；导出上限 10000 行
 *       （并受 acme.excel.max-rows 收窄），超限截断由 ExportResult.truncated 承载。</li>
 * </ul>
 */
@Service
public class OpExceptionUseCase {

    /** 系统级事件（无租户语境）的租户归一保留值（AuditIngestUseCase 同口径）。 */
    public static final String SYSTEM_TENANT = "system";

    /** 收集侧堆栈摘要截断上限（审计内容 512 先例同量级；发布侧已先截 8KB）。 */
    static final int STACK_SUMMARY_MAX_LENGTH = 512;

    /** 列宽截断：message 1024 / path 256 / exception_type 256 / method 8 / service 32。 */
    private static final int MESSAGE_MAX = 1024;
    private static final int PATH_MAX = 256;
    private static final int EXCEPTION_TYPE_MAX = 256;
    private static final int METHOD_MAX = 8;
    private static final int SERVICE_MAX = 32;

    private static final Logger log = LoggerFactory.getLogger(OpExceptionUseCase.class);

    private final OpExceptionRepository repository;
    private final OpExceptionDedupStore dedupStore;
    private final TransactionTemplate transactionTemplate;
    private final ExcelProperties excelProperties;

    public OpExceptionUseCase(OpExceptionRepository repository, OpExceptionDedupStore dedupStore,
                              PlatformTransactionManager transactionManager, ExcelProperties excelProperties) {
        this.repository = repository;
        this.dedupStore = dedupStore;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.excelProperties = excelProperties;
    }

    /** 收集落库（去重 → 租户绑定作用域内事务落库；调用方异步吞错）。 */
    public void collect(OpExceptionCapturedEvent event) {
        if (event == null) {
            return;
        }
        if (!dedupStore.tryAcquire(event.service(), event.exceptionType(), event.path())) {
            log.debug("异常日志去重窗口内跳过：service={} type={}", event.service(), event.exceptionType());
            return;
        }
        TenantContext previous = TenantContext.get();
        TenantContext.bind(new TenantContext(normalizeTenant(event.tenantId())));
        try {
            transactionTemplate.executeWithoutResult(status -> repository.save(toEntity(event)));
        } catch (DataIntegrityViolationException ex) {
            log.warn("异常日志落库约束冲突（保留事件日志不影响业务）：{}", ex.getMessage());
        } finally {
            TenantContext.bind(previous);
        }
    }

    /** 分页筛选查询（service/traceId/exceptionType 精确 + occurred_at 区间；倒序，本租户自动过滤）。 */
    public PageResult<OpException> page(PageQuery query, String service, String traceId,
                                        String exceptionType, Instant from, Instant to) {
        QueryWrapper wrapper = filterWrapper(service, traceId, exceptionType, from, to)
                .orderBy(OpException::getOccurredAt, false)
                .orderBy(OpException::getId, false);
        return repository.findAll(wrapper, query.page(), query.size());
    }

    /** 详情（含 stackSummary 全文；不存在 OP_EXCEPTION_NOT_FOUND）。 */
    public OpException get(Long exceptionId) {
        return repository.findById(exceptionId)
                .orElseThrow(() -> new BizException(InfraError.OP_EXCEPTION_NOT_FOUND));
    }

    /** 导出（同筛选参数；上限 10000 行并受 acme.excel.max-rows 收窄，超限截断标记承载）。 */
    public ExportResult<OpException> export(String service, String traceId,
                                            String exceptionType, Instant from, Instant to) {
        long cap = ExportResult.cap(excelProperties);
        List<OpException> fetched = repository
                .findAll(filterWrapper(service, traceId, exceptionType, from, to)
                        .orderBy(OpException::getOccurredAt, false)
                        .orderBy(OpException::getId, false), 1, (int) (cap + 1)).records();
        return ExportResult.of(fetched, cap);
    }

    private QueryWrapper filterWrapper(String service, String traceId,
                                       String exceptionType, Instant from, Instant to) {
        QueryWrapper wrapper = QueryWrapper.create();
        boolean hasCondition = false;
        if (service != null && !service.isBlank()) {
            wrapper.where(OpException::getService).eq(service.trim());
            hasCondition = true;
        }
        if (traceId != null && !traceId.isBlank()) {
            if (hasCondition) {
                wrapper.and(OpException::getTraceId).eq(traceId.trim());
            } else {
                wrapper.where(OpException::getTraceId).eq(traceId.trim());
                hasCondition = true;
            }
        }
        if (exceptionType != null && !exceptionType.isBlank()) {
            if (hasCondition) {
                wrapper.and(OpException::getExceptionType).eq(exceptionType.trim());
            } else {
                wrapper.where(OpException::getExceptionType).eq(exceptionType.trim());
                hasCondition = true;
            }
        }
        if (from != null) {
            if (hasCondition) {
                wrapper.and(OpException::getOccurredAt).ge(from);
            } else {
                wrapper.where(OpException::getOccurredAt).ge(from);
                hasCondition = true;
            }
        }
        if (to != null) {
            if (hasCondition) {
                wrapper.and(OpException::getOccurredAt).le(to);
            } else {
                wrapper.where(OpException::getOccurredAt).le(to);
            }
        }
        return wrapper;
    }

    /** 事件 → 实体（列宽截断；堆栈摘要收集侧 512 收口）。 */
    private static OpException toEntity(OpExceptionCapturedEvent event) {
        return new OpException(
                truncate(event.service(), SERVICE_MAX),
                event.traceId(),
                event.spanId(),
                event.operatorId(),
                truncate(event.method(), METHOD_MAX),
                truncate(event.path(), PATH_MAX),
                event.statusCode(),
                truncate(event.exceptionType(), EXCEPTION_TYPE_MAX),
                truncate(event.message(), MESSAGE_MAX),
                truncate(event.stackSummary(), STACK_SUMMARY_MAX_LENGTH),
                event.occurredAt());
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /** 租户归一：payload 为空视为系统级事件（AuditIngestUseCase 同口径）。 */
    private static String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? SYSTEM_TENANT : tenantId;
    }
}
