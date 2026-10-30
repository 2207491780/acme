package com.acme.framework.jpa.support;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 测试验证仓储（仅测试域）。
 */
public interface JpaAuditProbeRepository extends JpaRepository<JpaAuditProbe, Long> {
}
