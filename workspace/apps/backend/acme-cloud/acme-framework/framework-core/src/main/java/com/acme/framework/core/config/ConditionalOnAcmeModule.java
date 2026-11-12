package com.acme.framework.core.config;

import com.acme.framework.core.internal.AcmeModuleCondition;
import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 模块开关条件（FR-005；Modules.md 3.5 可关闭性）：{@code value} = 模块名，
 * 映射 {@code acme.<module>.enabled}，缺省视为开启（matchIfMissing，Q-001）。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(AcmeModuleCondition.class)
public @interface ConditionalOnAcmeModule {

    /** 模块名，对应配置键 {@code acme.<module>.enabled}（FR-005）。 */
    String value();
}
