package com.acme.framework.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

interface TenantProbeRepository extends JpaRepository<TenantProbe, Long> {
}
