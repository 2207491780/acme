package com.acme.framework.tenant.internal;

import org.hibernate.cfg.MultiTenancySettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;

import java.util.Map;

/**
 * Hibernate 装配适配（架构设计 5.1：注册 TenantIdentifierResolver；适配点核验——
 * Hibernate 7 接缝为 {@code hibernate.tenant_identifier_resolver} / root tenant 旁路）。
 */
public class HibernateTenantBridge implements HibernatePropertiesCustomizer {

    private final Object resolver;

    public HibernateTenantBridge(Object resolver) {
        this.resolver = resolver;
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
    }
}
