package com.acme.framework.flex.app;

import com.acme.framework.flex.support.AuditBase;
import com.mybatisflex.annotation.Table;

/**
 * 审计探针实体（切片测试专用，10.3）：覆盖基类字段填充、逻辑删除与乐观锁语义。
 */
@Table("flex_audit_probe")
public class FlexAuditProbe extends AuditBase {

    private String name;

    private Long amount;

    private Long seq;

    public FlexAuditProbe() {
    }

    public FlexAuditProbe(String name, Long amount, Long seq) {
        this.name = name;
        this.amount = amount;
        this.seq = seq;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getAmount() {
        return amount;
    }

    public void setAmount(Long amount) {
        this.amount = amount;
    }

    public Long getSeq() {
        return seq;
    }

    public void setSeq(Long seq) {
        this.seq = seq;
    }
}
