package com.acme.gateway;

import com.acme.framework.core.support.RateLimitOnError;
import com.acme.framework.gateway.config.GatewayProperties;
import com.acme.framework.gateway.support.UserTokenRevocationChecker;
import com.acme.gateway.auth.RedisUserTokenRevocationChecker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关应用配置静态核对测试（FR-010 / FR-012 / FR-014 / AC-010 / AC-012；
 * Q-D07 路由清理回归：sample 死路由不得回流）。
 */
@SpringBootTest(classes = AcmeGatewayApplication.class, properties = {
        // 测试域不连 Nacos / 不注入真实密钥（14.3.3：测试占位密钥，非生产取值）
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.gateway.user-token.hmac-secret=gateway-routing-config-test-secret-0123456789",
        "acme.security.token.hmac-secret=gateway-routing-config-test-secret-0123456789"
})
class GatewayRoutingConfigTest {

    @Autowired
    private RouteDefinitionLocator routeDefinitionLocator;

    @Autowired
    private GatewayProperties gatewayProperties;

    @Autowired
    private UserTokenRevocationChecker revocationChecker;

    @Test
    void routesRegisteredByCapabilityDomainWithLbUri() {
        List<RouteDefinition> routes = routeDefinitionLocator.getRouteDefinitions().collectList().block();
        assertThat(routes).isNotNull();
        Set<String> ids = routes.stream().map(RouteDefinition::getId).collect(Collectors.toSet());
        // 按能力域注册的六条路由（11.1.4 仅追加；Q-D07：sample 死路由已移除；
        // platform-infra 增补经 platform-infra-audit 拆分评审；
        // platform-auth-app 为 C 端双端前缀路由，consumer-auth-refresh-dataperm）
        // business-content / business-content-app 为内容域双端路由（business-api-integration G2.5）
        assertThat(ids).containsExactlyInAnyOrder("platform-auth", "platform-system", "platform-infra",
                "platform-auth-app", "business-content", "business-content-app");
        RouteDefinition auth = routes.stream()
                .filter(r -> r.getId().equals("platform-auth")).findFirst().orElseThrow();
        assertThat(auth.getUri().toString()).isEqualTo("lb://platform-auth");
        RouteDefinition system = routes.stream().filter(r -> r.getId().equals("platform-system")).findFirst().orElseThrow();
        assertThat(system.getUri().toString()).isEqualTo("lb://platform-system");
        RouteDefinition infra = routes.stream().filter(r -> r.getId().equals("platform-infra")).findFirst().orElseThrow();
        assertThat(infra.getUri().toString()).isEqualTo("lb://platform-infra");
        RouteDefinition authApp = routes.stream().filter(r -> r.getId().equals("platform-auth-app")).findFirst().orElseThrow();
        assertThat(authApp.getUri().toString()).isEqualTo("lb://platform-auth");
        RouteDefinition content = routes.stream()
                .filter(r -> r.getId().equals("business-content")).findFirst().orElseThrow();
        assertThat(content.getUri().toString()).isEqualTo("lb://business-content");
        RouteDefinition contentApp = routes.stream()
                .filter(r -> r.getId().equals("business-content-app")).findFirst().orElseThrow();
        assertThat(contentApp.getUri().toString()).isEqualTo("lb://business-content");
    }

    @Test
    void publicPathsAreMinimalExplicitList() {
        // FR-012 最小化：B 端仅登录豁免；C 端为认证自证 + 内容读（Q-12=A）豁免（business-api-integration 追加）
        assertThat(gatewayProperties.getAuth().getB().getPublicPaths())
                .containsExactly("/admin/v1/auth/tokens");
        assertThat(gatewayProperties.getAuth().getC().getPublicPaths()).containsExactly(
                "/app/v1/auth/captcha", "/app/v1/auth/tokens", "/app/v1/auth/tokens/refresh",
                "/app/v1/feeds", "/app/v1/hot-searches", "/app/v1/contents/**", "/app/v1/search/**",
                "/app/v1/categories");
    }

    @Test
    void perChannelRatelimitDefaultsExplicit() {
        // Q-006 裁定 B：按端限流默认策略显式写出（AC-012 静态核对 + 单测锁定）
        assertThat(gatewayProperties.getRatelimit().getB().getQuotaPerMinute()).isEqualTo(120);
        assertThat(gatewayProperties.getRatelimit().getC().getQuotaPerMinute()).isEqualTo(240);
        assertThat(gatewayProperties.getRatelimit().getFallback().getQuotaPerMinute()).isEqualTo(60);
    }

    @Test
    void revocationCheckerIsRedisImplementationWithFailOpen() {
        // Q-D02：网关应用注册 Redis 直查实现覆盖缺省 no-op；异常兜底 FAIL_OPEN
        assertThat(revocationChecker).isInstanceOf(RedisUserTokenRevocationChecker.class);
        assertThat(gatewayProperties.getRevocation().isEnabled()).isTrue();
        assertThat(gatewayProperties.getRevocation().getOnError()).isEqualTo(RateLimitOnError.FAIL_OPEN);
    }
}
