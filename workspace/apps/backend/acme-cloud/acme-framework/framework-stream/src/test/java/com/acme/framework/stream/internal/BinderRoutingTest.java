package com.acme.framework.stream.internal;

import com.acme.common.core.BizException;
import com.acme.framework.stream.config.StreamProperties;
import com.acme.framework.stream.support.StreamError;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多 binder 路由与死信启动校验（FR-015/016、AC-016/017）。
 */
class BinderRoutingTest {

    @Test
    void unconfiguredBindingFallsBackToDefaultBinder() {
        assertThatCode(() -> new BinderRouting(new StandardEnvironment()).validate("out-0"))
                .doesNotThrowAnyException();
    }

    @Test
    void unknownBinderRejected() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("t", Map.of(
                "spring.cloud.stream.bindings.out-0.binder", "ghost")));
        assertThatThrownBy(() -> new BinderRouting(environment).validate("out-0"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode()).isEqualTo(StreamError.SEND_FAILED));
    }

    @Test
    void blankBindingRejected() {
        assertThatThrownBy(() -> new BinderRouting(new StandardEnvironment()).validate(" "))
                .isInstanceOf(BizException.class);
    }

    @Test
    void kafkaInputWithoutDlqFailsWhenRequired() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("t", Map.of(
                "spring.cloud.stream.bindings.demo-in-0.destination", "topic",
                "spring.cloud.stream.bindings.demo-in-0.binder", "kafka-binder",
                "spring.cloud.stream.binders.kafka-binder.type", "kafka")));
        assertThatThrownBy(() -> new DlqConfigValidator(environment, new StreamProperties()).validate())
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                        .isEqualTo(StreamError.DLQ_NOT_CONFIGURED));
    }

    @Test
    void kafkaInputWithDlqPasses() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("t", Map.of(
                "spring.cloud.stream.bindings.demo-in-0.destination", "topic",
                "spring.cloud.stream.bindings.demo-in-0.binder", "kafka-binder",
                "spring.cloud.stream.binders.kafka-binder.type", "kafka",
                "spring.cloud.stream.kafka.bindings.demo-in-0.consumer.enable-dlq", "true")));
        assertThatCode(() -> new DlqConfigValidator(environment, new StreamProperties()).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void rocketmqInputReliesOnNativeDlq() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("t", Map.of(
                "spring.cloud.stream.bindings.demo-in-0.destination", "topic",
                "spring.cloud.stream.bindings.demo-in-0.binder", "rocketmq-binder",
                "spring.cloud.stream.binders.rocketmq-binder.type", "rocketmq")));
        assertThatCode(() -> new DlqConfigValidator(environment, new StreamProperties()).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void dlqNotRequiredDowngradesToWarn() {
        StreamProperties properties = new StreamProperties();
        properties.getDlq().setRequired(false);
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("t", Map.of(
                "spring.cloud.stream.bindings.demo-in-0.destination", "topic",
                "spring.cloud.stream.bindings.demo-in-0.binder", "kafka-binder",
                "spring.cloud.stream.binders.kafka-binder.type", "kafka")));
        assertThatCode(() -> new DlqConfigValidator(environment, properties).validate())
                .doesNotThrowAnyException();
    }
}
