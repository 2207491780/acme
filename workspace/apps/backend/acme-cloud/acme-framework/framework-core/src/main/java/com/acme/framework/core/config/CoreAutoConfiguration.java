package com.acme.framework.core.config;

import com.acme.framework.core.internal.AfterCommitEventDispatcher;
import com.acme.framework.core.internal.ApplicationContextRegistry;
import com.acme.framework.core.internal.DefaultTransactionEventPublisher;
import com.acme.framework.core.internal.ManagedThreadPoolFactory;
import com.acme.framework.core.support.AfterCommitEventListener;
import com.acme.framework.core.support.CoreExecutor;
import com.acme.framework.core.support.KeyComposer;
import com.acme.framework.core.support.TransactionEventPublisher;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.List;

/**
 * framework-core 总装（FR-005 自举；FR-001~FR-004 装配），经 AutoConfiguration.imports 注册（BR-004）。
 */
@AutoConfiguration
@ConditionalOnAcmeModule("core")
@EnableConfigurationProperties(CoreProperties.class)
public class CoreAutoConfiguration extends AcmeModuleAutoConfiguration {

    /** 受管异步执行门面（FR-001；线程池默认使用 ContextTaskDecorator，FR-002）。 */
    @Bean
    public CoreExecutor coreExecutor(CoreProperties properties) {
        return new CoreExecutor(ManagedThreadPoolFactory.create(properties.getExecutor()));
    }

    /** 事务提交后事件发布（FR-003）。 */
    @Bean
    public TransactionEventPublisher transactionEventPublisher(List<AfterCommitEventListener<?>> listeners) {
        return new DefaultTransactionEventPublisher(new AfterCommitEventDispatcher(listeners));
    }

    /** 应用上下文持有者（FR-004）。 */
    @Bean
    public ApplicationContextRegistry applicationContextRegistry() {
        return new ApplicationContextRegistry();
    }

    /** 键构造规范（Modules.md 4.3 下沉共享，P4 内部零横向依赖）。 */
    @Bean
    public KeyComposer keyComposer(Environment environment) {
        return new KeyComposer(environment);
    }
}
