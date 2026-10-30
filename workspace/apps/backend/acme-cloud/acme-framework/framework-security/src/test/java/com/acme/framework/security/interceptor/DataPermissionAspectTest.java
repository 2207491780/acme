package com.acme.framework.security.interceptor;

import com.acme.common.core.BizException;
import com.acme.common.context.UserContext;
import com.acme.framework.security.support.DataPermission;
import com.acme.framework.security.support.DataPermissionDecision;
import com.acme.framework.security.support.DataPermissionEvaluator;
import com.acme.framework.security.support.SecurityError;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据权限拦截测试（FR-017、Q-D09、AC-014）。
 */
class DataPermissionAspectTest {

    static class ProtectedTarget {
        @DataPermission("sample.read")
        public String read() {
            return "ok";
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class AspectConfig {
        @Bean
        DataPermissionAspect dataPermissionAspect() {
            return new DataPermissionAspect(emptyProvider());
        }

        @Bean
        ProtectedTarget protectedTarget() {
            return new ProtectedTarget();
        }

        static ObjectProvider<DataPermissionEvaluator> emptyProvider() {
            return new ObjectProvider<>() {
                @Override
                public DataPermissionEvaluator getObject(Object... args) {
                    return null;
                }

                @Override
                public DataPermissionEvaluator getIfAvailable() {
                    return null;
                }

                @Override
                public DataPermissionEvaluator getIfUnique() {
                    return null;
                }

                @Override
                public DataPermissionEvaluator getObject() {
                    return null;
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class DenyConfig {
        @Bean
        DataPermissionEvaluator evaluator() {
            return context -> DataPermissionDecision.DENY;
        }

        @Bean
        DataPermissionAspect dataPermissionAspect(ObjectProvider<DataPermissionEvaluator> provider) {
            return new DataPermissionAspect(provider);
        }

        @Bean
        ProtectedTarget protectedTarget() {
            return new ProtectedTarget();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class ThrowingConfig {
        @Bean
        DataPermissionEvaluator evaluator() {
            return context -> {
                throw new IllegalStateException("rule broken");
            };
        }

        @Bean
        DataPermissionAspect dataPermissionAspect(ObjectProvider<DataPermissionEvaluator> provider) {
            return new DataPermissionAspect(provider);
        }

        @Bean
        ProtectedTarget protectedTarget() {
            return new ProtectedTarget();
        }
    }

    @Test
    void noEvaluatorAllows() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(AspectConfig.class)) {
            assertThat(context.getBean(ProtectedTarget.class).read()).isEqualTo("ok");
        }
    }

    @Test
    void denyEvaluatorBlocks() {
        UserContext.bind(new UserContext("u-1", Set.of()));
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(DenyConfig.class)) {
            assertThatThrownBy(() -> context.getBean(ProtectedTarget.class).read())
                    .isInstanceOf(BizException.class)
                    .hasFieldOrPropertyWithValue("errorCode", SecurityError.DATA_PERMISSION_DENIED);
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void evaluatorFailureDenies() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(ThrowingConfig.class)) {
            assertThatThrownBy(() -> context.getBean(ProtectedTarget.class).read())
                    .isInstanceOf(BizException.class)
                    .hasFieldOrPropertyWithValue("errorCode", SecurityError.DATA_PERMISSION_DENIED);
        }
    }
}
