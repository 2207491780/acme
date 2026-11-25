package com.acme.platform.infra.domain;

import com.acme.framework.flex.support.AuditBase;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 跨租户审计落库行（REQ-20261125-001736 AC-3；Q-005 修订：旁路审计异步尽力落库）：
 * 行不可变——无软删语义（deleted 恒 false）、不提供修改删除面。
 *
 * <p>平台级跨租户数据：tenant_id 为事件记录值（旁路行记 {@code *}，直读行记实际租户/缺省 {@code -}），
 * <b>不启用</b> {@code @Column(tenantId=true)}——旁路审计不参与行级租户过滤，落库经 sink 在无
 * 租户上下文线程直写（TenantWriteGuard 只拦含租户列实体）。</p>
 */
@Table("sys_cross_tenant_audit")
public class CrossTenantAuditRow extends AuditBase {

    /** 操作人（缺失归一 system，与事件载体一致）。 */
    @Column("operator")
    private String operator;

    /** 事件租户记录值（旁路=*, 直读=租户/缺省 -；非行级租户列）。 */
    @Column("tenant_id")
    private String tenantId;

    /** 访问点标识（12.4.2 审计必填）。 */
    @Column("access_point")
    private String accessPoint;

    /** 链路 traceId（缺失记 -）。 */
    @Column("trace_id")
    private String traceId;

    /** 旁路标记（ROOT=旁路开启 / false=直读，与同步日志逐字段对齐）。 */
    @Column("bypass")
    private String bypass;

    protected CrossTenantAuditRow() {
    }

    public CrossTenantAuditRow(String operator, String tenantId, String accessPoint,
                               String traceId, String bypass) {
        this.operator = operator;
        this.tenantId = tenantId;
        this.accessPoint = accessPoint;
        this.traceId = traceId;
        this.bypass = bypass;
    }

    public String getOperator() {
        return operator;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getAccessPoint() {
        return accessPoint;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getBypass() {
        return bypass;
    }
}
