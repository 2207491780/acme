package com.acme.framework.jpa.internal;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;

/**
 * 软删除适配注册（6.3：internal 适配层落删除改写；适配点核验于开发时——Hibernate 7 Integrator SPI，
 * ServiceLoader 发现）：向 PRE_DELETE 事件注册 {@link SoftDeletePreDeleteListener}。
 */
public class SoftDeleteIntegrator implements Integrator {

    @Override
    public void integrate(Metadata metadata, BootstrapContext bootstrapContext,
                          SessionFactoryImplementor sessionFactory) {
        EventListenerRegistry registry = sessionFactory.getServiceRegistry()
                .getService(EventListenerRegistry.class);
        registry.prependListeners(EventType.PRE_DELETE, new SoftDeletePreDeleteListener());
    }
}
