package com.acme.gateway;

import com.acme.framework.core.support.RateLimitOnError;
import com.acme.framework.gateway.config.GatewayProperties;
import com.acme.framework.gateway.support.UserTokenRevocationChecker;
import com.acme.gateway.auth.RedisUserTokenRevocationChecker;
import com.acme.gateway.doc.GatewayDocProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.core.env.Environment;

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

    @Autowired
    private GatewayDocProperties docProperties;

    @Autowired
    private Environment environment;

    @Test
    void routesRegisteredByCapabilityDomainWithLbUri() {
        List<RouteDefinition> routes = routeDefinitionLocator.getRouteDefinitions().collectList().block();
        assertThat(routes).isNotNull();
        Set<String> ids = routes.stream().map(RouteDefinition::getId).collect(Collectors.toSet());
        // 按能力域注册的六条路由（11.1.4 仅追加；Q-D07：sample 死路由已移除；
        // platform-infra 增补经 platform-infra-audit 拆分评审；
        // platform-auth-app 为 C 端双端前缀路由，consumer-auth-refresh-dataperm；
        // platform-system-ws 为 WS 实时推送路由，REQ-20261114-120102；
        // platform-scheduler 为调度中心路由，REQ-20261121-071002 确认⑥；
        // platform-workflow 为 BPM 工作流路由，REQ-20261121-142316 确认⑧）；
        // 五条聚合文档转发路由（REQ-20261114-120106、AC-2 基线三条
        // + REQ-20261121-071002 确认⑥调度中心分组 platform-scheduler-doc
        // + REQ-20261121-142316 确认⑧工作流分组 platform-workflow-doc）；
        // business-content / business-content-app 内容域双端路由已随业务域下线移除
        assertThat(ids).containsExactlyInAnyOrder("platform-auth", "platform-system", "platform-infra",
                "platform-scheduler", "platform-workflow", "platform-auth-app", "platform-system-ws",
                "platform-auth-doc", "platform-system-doc", "platform-infra-doc", "platform-scheduler-doc",
                "platform-workflow-doc");
        RouteDefinition auth = routes.stream()
                .filter(r -> r.getId().equals("platform-auth")).findFirst().orElseThrow();
        assertThat(auth.getUri().toString()).isEqualTo("lb://platform-auth");
        RouteDefinition system = routes.stream().filter(r -> r.getId().equals("platform-system")).findFirst().orElseThrow();
        assertThat(system.getUri().toString()).isEqualTo("lb://platform-system");
        RouteDefinition systemWs = routes.stream().filter(r -> r.getId().equals("platform-system-ws")).findFirst().orElseThrow();
        assertThat(systemWs.getUri().toString()).isEqualTo("lb://platform-system");
        RouteDefinition infra = routes.stream().filter(r -> r.getId().equals("platform-infra")).findFirst().orElseThrow();
        assertThat(infra.getUri().toString()).isEqualTo("lb://platform-infra");
        RouteDefinition authApp = routes.stream().filter(r -> r.getId().equals("platform-auth-app")).findFirst().orElseThrow();
        assertThat(authApp.getUri().toString()).isEqualTo("lb://platform-auth");
    }

    @Test
    void publicPathsAreMinimalExplicitList() {
        // FR-012 最小化：B 端仅图形验证码 + 验证码策略 + 公开站点配置 + 租户目录 + 登录豁免
        // （login-guard-captcha / login-tenant-select / REQ-20261117-011514 captcha-policy /
        // REQ-20261117-025200 public-config 页脚版权）+ 扫码票据创建/轮询（REQ-20261119-040838
        // 确认记录④：精确创建 + 单段通配轮询，info/confirm/cancel 双段后缀不豁免仍验签）；
        // C 端仅认证自证豁免（内容读豁免随业务域下线移除）
        assertThat(gatewayProperties.getAuth().getB().getPublicPaths()).containsExactly(
                "/admin/v1/auth/captcha", "/admin/v1/auth/captcha-policy",
                "/admin/v1/auth/public-config", "/admin/v1/auth/tenants", "/admin/v1/auth/tokens",
                "/admin/v1/auth/qr-tickets", "/admin/v1/auth/qr-tickets/*");
        assertThat(gatewayProperties.getAuth().getC().getPublicPaths()).containsExactly(
                "/app/v1/auth/captcha", "/app/v1/auth/tokens", "/app/v1/auth/tokens/refresh");
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

    @Test
    void aggregatedDocRoutesForwardToPlatformServices() {
        // REQ-20261114-120106、AC-1/AC-2：/v3/api-docs/{service} → lb://platform-{service}，SetPath 归位 openapi 端点
        List<String> docRouteIds = List.of("platform-auth-doc", "platform-system-doc", "platform-infra-doc");
        List<RouteDefinition> routes = routeDefinitionLocator.getRouteDefinitions().collectList().block();
        assertThat(routes).isNotNull();
        for (String id : docRouteIds) {
            RouteDefinition doc = routes.stream().filter(r -> r.getId().equals(id)).findFirst().orElseThrow();
            String service = id.substring(0, id.length() - "-doc".length());
            assertThat(doc.getUri().toString()).isEqualTo("lb://" + service);
            assertThat(doc.getPredicates().stream()
                    .anyMatch(p -> "Path".equals(p.getName())
                            && ("/v3/api-docs/" + service.substring("platform-".length())).equals(p.getArgs().get("_genkey_0"))))
                    .as("路由 %s 应以 Path=/v3/api-docs/{service} 为谓词", id).isTrue();
            assertThat(doc.getFilters().stream()
                    .anyMatch(f -> "SetPath".equals(f.getName()) && "/v3/api-docs".equals(f.getArgs().get("_genkey_0"))))
                    .as("路由 %s 应以 SetPath=/v3/api-docs 归位路径", id).isTrue();
        }
    }

    @Test
    void aggregatedDocSwitchDefaultsOnWithGroupedUi() {
        // REQ-20261114-120106、AC-1：acme.doc.enabled 单一配置源（默认 true）；
        // /docs UI 端点启用且按 auth/system/infra 分组下拉；网关自身 openapi 产出关闭
        assertThat(docProperties.isEnabled()).isTrue();
        assertThat(environment.getProperty("springdoc.swagger-ui.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("springdoc.swagger-ui.path")).isEqualTo("/docs");
        assertThat(environment.getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
    }
}
