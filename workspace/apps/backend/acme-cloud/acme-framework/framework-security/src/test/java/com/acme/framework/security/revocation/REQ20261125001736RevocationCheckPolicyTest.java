package com.acme.framework.security.revocation;

import com.acme.framework.security.config.SecurityAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261125-001736 AC-7/R2：撤销检查 fail-closed 开关装配（SecurityAutoConfiguration）——
 * 配置键 {@code acme.security.revocation.fail-closed} 默认 false（与现状放行+WARN 完全一致）；
 * true 绑定生效；模块关闭不装配。网关（RevocationCheckFilter）与边缘（BootstrapAuthFilter）
 * 共用同型 policy 语义，行为断言见 gateway/edge 两份用例。
 *
 * <p>口径说明（已随交付对齐，原并行假设销项）：policy 由 SecurityAutoConfiguration 注册
 * （SecuritySwitchTest 同缝）；访问器 {@code rejectOnCheckFailure()}（交付钉名）。</p>
 */
class REQ20261125001736RevocationCheckPolicyTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SecurityAutoConfiguration.class))
            .withPropertyValues("acme.security.token.hmac-secret=it-req001736-test-hmac-secret-0123456789abcdef");

    // ---------- R2：默认 false（与现状一致） ----------

    @Test
    void should_defaultFailClosedToFalse_when_zeroConfig() {
        runner.run(context -> {
            assertThat(context).as("启用态装配 RevocationCheckPolicy").hasSingleBean(RevocationCheckPolicy.class);
            assertThat(context.getBean(RevocationCheckPolicy.class).rejectOnCheckFailure())
                    .as("默认 false：Redis 检查异常放行+WARN（现状语义）").isFalse();
        });
    }

    // ---------- R2：true 绑定生效 ----------

    @Test
    void should_bindFailClosedTrue_when_propertySet() {
        runner.withPropertyValues("acme.security.revocation.fail-closed=true")
                .run(context -> assertThat(context.getBean(RevocationCheckPolicy.class).rejectOnCheckFailure())
                        .as("fail-closed=true：Redis 检查异常拒绝（503+REVOCATION_CHECK_UNAVAILABLE）")
                        .isTrue());
    }

    // ---------- 模块开关联动（AMB-2） ----------

    @Test
    void should_notAssemblePolicy_when_securityDisabled() {
        runner.withPropertyValues("acme.security.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(RevocationCheckPolicy.class));
    }
}
