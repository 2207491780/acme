package com.acme.framework.tenant.internal;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;

/**
 * 未绑定租户守卫注册（Hibernate 7 Integrator SPI、ServiceLoader 发现；SoftDeleteIntegrator 同型）：
 * 向 PRE_INSERT 事件注册 {@link UnboundTenantGuard}。
 */
public class UnboundTenantGuardIntegrator implements Integrator {

    @Override
    public void integrate(Metadata metadata, BootstrapContext bootstrapContext,
                          SessionFactoryImplementor sessionFactory) {
        EventListenerRegistry registry = sessionFactory.getServiceRegistry()
                .getService(EventListenerRegistry.class);
        registry.prependListeners(EventType.PRE_INSERT, new UnboundTenantGuard());
    }
}
