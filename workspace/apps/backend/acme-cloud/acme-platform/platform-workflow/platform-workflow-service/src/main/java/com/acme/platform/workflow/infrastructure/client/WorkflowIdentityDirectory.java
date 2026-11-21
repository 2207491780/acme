package com.acme.platform.workflow.infrastructure.client;

import com.acme.common.context.TenantContext;
import com.acme.platform.system.api.ContractResult;
import com.acme.platform.system.api.WorkflowIdentityClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流程引擎身份目录（「流程引擎本地快照」方案，REQ-20261121-142316 确认④配套）：
 * UserTask 候选解析与 SpEL 内置函数 hasRole/inDept 的数据源——经 {@link WorkflowIdentityClient}
 * Feign 查 platform-system（role/dept 表跨服务独占），查询结果按 (tenantId,userId)/键维度做
 * 短 TTL 本地快照缓存（避免同实例多次解析反复跨服务取数）；缓存失败/未命中直查。
 * fail-closed：目录服务不可达按空集处置（无候选→NO_ASSIGNEE_RESOLVED 挂起，不放行）。
 * 显式 bean 名消歧（REQ-20261118-002130 惯例）：与 bootstrap 本地适配器在单体裁装下不冲突
 * （本类 workflow 域专属命名）。
 */
@Component
public class WorkflowIdentityDirectory {

    private static final Logger log = LoggerFactory.getLogger(WorkflowIdentityDirectory.class);

    private final ObjectProvider<WorkflowIdentityClient> identityClients;
    private final com.acme.common.core.Clock clock;
    private final com.acme.platform.workflow.config.WorkflowProperties properties;
    private final Map<String, SnapshotEntry> snapshots = new ConcurrentHashMap<>();

    public WorkflowIdentityDirectory(ObjectProvider<WorkflowIdentityClient> identityClients,
                                     com.acme.common.core.Clock workflowClock,
                                     com.acme.platform.workflow.config.WorkflowProperties workflowProperties) {
        this.identityClients = identityClients;
        this.clock = workflowClock;
        this.properties = workflowProperties;
    }

    /** 用户角色编码集（快照缓存 TTL 内直取）。 */
    public Set<String> roleCodesOf(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        return cached("roles:" + tenantKey() + ":" + userId,
                () -> safeQuery("role-codes:" + userId,
                        () -> identityClients.getObject().userRoleCodes(userId),
                        result -> result != null && Boolean.TRUE.equals(result.success())
                                && result.data() != null ? Set.copyOf(result.data()) : Set.of(),
                        Set.of()));
    }

    /** 用户主属部门 id（未归属返回 null）。 */
    public Long deptIdOf(Long userId) {
        if (userId == null) {
            return null;
        }
        return cached("dept:" + tenantKey() + ":" + userId,
                () -> safeQuery("dept-id:" + userId,
                        () -> identityClients.getObject().userDeptId(userId),
                        result -> result != null && Boolean.TRUE.equals(result.success())
                                ? result.data() : null,
                        null));
    }

    /** 角色编码 → 用户 id 集（未知编码=空集）。 */
    public Set<Long> userIdsByRole(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return Set.of();
        }
        return cached("role-users:" + tenantKey() + ":" + roleCode,
                () -> safeQuery("role-users:" + roleCode,
                        () -> identityClients.getObject().roleUserIds(roleCode.trim()),
                        result -> result != null && Boolean.TRUE.equals(result.success())
                                && result.data() != null ? Set.copyOf(result.data()) : Set.of(),
                        Set.of()));
    }

    /** 部门 id → 用户 id 集（未知部门=空集）。 */
    public Set<Long> userIdsByDept(Long deptId) {
        if (deptId == null) {
            return Set.of();
        }
        return cached("dept-users:" + tenantKey() + ":" + deptId,
                () -> safeQuery("dept-users:" + deptId,
                        () -> identityClients.getObject().deptUserIds(deptId),
                        result -> result != null && Boolean.TRUE.equals(result.success())
                                && result.data() != null ? Set.copyOf(result.data()) : Set.of(),
                        Set.of()));
    }

    /**
     * 目录查询统一降级面（fail-closed——REQ-20261121-142316 确认④配套）：目录服务不可达/
     * 未注册/响应非成功一律按空结果处置并告警，不向命令面抛 500（调用方语义=无角色/无部门/
     * 无候选 → NO_ASSIGNEE_RESOLVED 挂起，不放行）。
     */
    private <R, T> T safeQuery(String accessPoint, java.util.function.Supplier<R> query,
                               java.util.function.Function<R, T> mapper, T fallback) {
        try {
            return mapper.apply(query.get());
        } catch (RuntimeException ex) {
            log.warn("身份目录查询失败（fail-closed 空结果）：accessPoint={} cause={}",
                    accessPoint, ex.getMessage());
            return fallback;
        }
    }

    /** 清空本地快照（管理面/测试辅助；分布式形态缓存随 TTL 自滚动，无远端失效通道）。 */
    public void evictAll() {
        snapshots.clear();
    }

    /** 快照读取与回填（TTL 内直取；超限绕过缓存直查）。 */
    private <T> T cached(String key, java.util.function.Supplier<T> loader) {
        long ttl = properties.getIdentity().getSnapshotTtlMs();
        if (ttl <= 0 || snapshots.size() >= properties.getIdentity().getSnapshotMaxEntries()) {
            return loader.get();
        }
        SnapshotEntry entry = snapshots.get(key);
        Instant now = clock.now();
        if (entry != null && entry.expiresAt().isAfter(now)) {
            @SuppressWarnings("unchecked")
            T value = (T) entry.value();
            return value;
        }
        T value = loader.get();
        snapshots.put(key, new SnapshotEntry(value, now.plusMillis(ttl)));
        return value;
    }

    /** 快照条目（值 + 过期时刻）。 */
    private record SnapshotEntry(Object value, Instant expiresAt) {
    }

    /** 快照键租户段（TenantContext 缺失时取 "default"，仅作缓存维度，不做数据过滤）。 */
    private static String tenantKey() {
        TenantContext tenant = TenantContext.get();
        return tenant == null || tenant.tenantId() == null ? "default" : tenant.tenantId();
    }
}
