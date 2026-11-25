package com.acme.framework.security.revocation;

import com.acme.framework.security.config.SecurityProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 撤销检查异常处置决策组件测试（REQ-20261125-001736 AC-7）：默认 false 与现状一致、
 * fail-closed 配置置 true 时拒绝；配置绑定面 acme.security.revocation.fail-closed。
 */
class RevocationCheckPolicyTest {

    @Test
    void should_failOpenByDefault_when_configAbsent() {
        SecurityProperties properties = new SecurityProperties();
        RevocationCheckPolicy policy = new RevocationCheckPolicy(properties.getRevocation().isFailClosed());

        assertThat(properties.getRevocation().isFailClosed()).isFalse();
        assertThat(policy.rejectOnCheckFailure()).isFalse();
    }

    @Test
    void should_reject_when_failClosedConfigured() {
        SecurityProperties properties = new SecurityProperties();
        properties.getRevocation().setFailClosed(true);
        RevocationCheckPolicy policy = new RevocationCheckPolicy(properties.getRevocation().isFailClosed());

        assertThat(policy.rejectOnCheckFailure()).isTrue();
    }
}
