package com.acme.framework.jpa.support;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 测试验证实体（仅测试域；框架不建业务表，BR-022——本实体为切片测试载体）。
 */
@Entity
@Table(name = "t_jpa_audit_probe")
@SoftDelete
public class JpaAuditProbe extends AuditBase {

    @Column(name = "name", length = 64, nullable = false)
    private String name;

    @Column(name = "amount")
    private Long amount;

    @Column(name = "seq")
    private Long seq;

    protected JpaAuditProbe() {
    }

    public JpaAuditProbe(String name, Long amount, Long seq) {
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

    public Long getSeq() {
        return seq;
    }
}
