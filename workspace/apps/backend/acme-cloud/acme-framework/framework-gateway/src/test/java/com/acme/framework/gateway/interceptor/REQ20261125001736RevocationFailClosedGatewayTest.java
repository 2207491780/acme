package com.acme.framework.gateway.interceptor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.acme.common.context.Channel;
import com.acme.framework.gateway.config.GatewayProperties;
import com.acme.framework.gateway.internal.ExchangeAttrs;
import com.acme.framework.gateway.internal.GatewayRejectedException;
import com.acme.framework.gateway.support.GatewayError;
import com.acme.framework.gateway.support.UserTokenClaims;
import com.acme.framework.gateway.support.UserTokenRevocationChecker;
import com.acme.framework.security.config.SecurityAutoConfiguration;
import com.acme.framework.security.revocation.RevocationCheckPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REQ-20261125-001736 AC-7/R2（网关侧）：Redis 撤销检查异常 × fail-closed 开关——
 * true=拒绝（HTTP 503 + 错误码 REVOCATION_CHECK_UNAVAILABLE）；默认 false=放行+WARN（现状）。
 * 装配：policy 经 SecurityAutoConfiguration 真装配提取（与边缘侧同开关同语义）；过滤器行为
 * 在既有 {@code RevocationCheckFilterTest} 缝上驱动（MockServerWebExchange + claims 属性）。
 *
 * <p>口径说明（并行实现假设就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-1（已随交付对齐）：RevocationCheckFilter(checker, revocation, policy) 三参构造、
 *       503 拒绝承载 GatewayRejectedException + GatewayError.REVOCATION_CHECK_UNAVAILABLE
 *       （交付 RevocationCheckFilterTest 同缝同常量）。</li>
 *   <li>AMB-2 WARN 断言：默认放行时 RevocationCheckFilter 类名 logger 出现 WARN（消息内容不钉死）。</li>
 * </ul>
 */
class REQ20261125001736RevocationFailClosedGatewayTest {

    private final GatewayProperties properties = new GatewayProperties();

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @AfterEach
    void detachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(RevocationCheckFilter.class);
        logger.detachAppender(appender);
    }

    // ---------- AC-7：fail-closed=true + Redis 检查异常 → 拒绝（503 + REVOCATION_CHECK_UNAVAILABLE） ----------

    @Test
    void should_rejectWith503AndRevocationUnavailable_when_checkerFailsAndFailClosedTrue() {
        RevocationCheckPolicy policy = autoAssembledPolicy(true);
        RevocationCheckFilter filter = new RevocationCheckFilter(tokenId -> {
            throw new IllegalStateException("redis down");
        }, properties.getRevocation(), policy);

        assertThatThrownBy(() -> filter.filter(exchangeWithClaims(claims("jti-001736")), pass()).block())
                .isInstanceOf(GatewayRejectedException.class)
                .satisfies(ex -> {
                    GatewayRejectedException rejected = (GatewayRejectedException) ex;
                    assertThat(rejected.httpStatus()).as("fail-closed 拒绝：HTTP 503").isEqualTo(503);
                    assertThat(rejected.errorCode()).isEqualTo(GatewayError.REVOCATION_CHECK_UNAVAILABLE);
                });
    }

    // ---------- AC-7：默认 false + Redis 检查异常 → 放行 + WARN（现状） ----------

    @Test
    void should_passWithWarnLog_when_checkerFailsAndFailClosedDefault() {
        RevocationCheckPolicy policy = autoAssembledPolicy(false);
        AtomicInteger forwarded = new AtomicInteger();
        GatewayFilterChain chain = exchange -> {
            forwarded.incrementAndGet();
            return Mono.empty();
        };
        RevocationCheckFilter filter = new RevocationCheckFilter(tokenId -> {
            throw new IllegalStateException("redis down");
        }, properties.getRevocation(), policy);

        Logger logger = (Logger) LoggerFactory.getLogger(RevocationCheckFilter.class);
        appender.start();
        logger.addAppender(appender);

        // 默认 FAIL：放行不拒绝（现状语义），链路继续
        filter.filter(exchangeWithClaims(claims("jti-001736")), chain).block();

        assertThat(forwarded.get()).as("默认放行：下游链路被调用").isEqualTo(1);
        assertThat(appender.list).as("放行伴随 WARN 日志（AMB-2：仅断言级别）")
                .anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
    }

    // ---------- 支撑 ----------

    /** policy 经 SecurityAutoConfiguration 真装配提取（与边缘侧同开关同语义）。 */
    private static RevocationCheckPolicy autoAssembledPolicy(boolean failClosed) {
        AtomicReference<RevocationCheckPolicy> ref = new AtomicReference<>();
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SecurityAutoConfiguration.class))
                .withPropertyValues("acme.security.token.hmac-secret=it-req001736-test-hmac-secret-0123456789abcdef");
        if (failClosed) {
            runner = runner.withPropertyValues("acme.security.revocation.fail-closed=true");
        }
        runner.run(context -> ref.set(context.getBean(RevocationCheckPolicy.class)));
        return ref.get();
    }

    private static MockServerWebExchange exchangeWithClaims(UserTokenClaims claims) {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/admin/v1/system/users"));
        if (claims != null) {
            exchange.getAttributes().put(ExchangeAttrs.USER_CLAIMS, claims);
        }
        return exchange;
    }

    private static UserTokenClaims claims(String tokenId) {
        return new UserTokenClaims("1", "default", Channel.B, Set.of("super_admin"), "access", tokenId);
    }

    private static GatewayFilterChain pass() {
        return exchange -> Mono.empty();
    }
}
