package com.acme.gateway.doc;

import com.acme.gateway.AcmeGatewayApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档转发路由形态与配置绑定核对（REQ-20261114-120106、AC-1/AC-2）：round1 打回单 AMB-3
 * 钉死——不存在单端点聚合，文档转发为逐服务路由（/v3/api-docs/{service} →
 * lb://platform-{service} + SetPath=/v3/api-docs，路由 id=platform-{service}-doc）；
 * acme.doc.enabled 默认 true（绑定 acme-gateway 的 GatewayDocProperties）。
 * 基线三条 {auth|system|infra}（REQ-20261114-120106）+ 调度中心分组第四条 scheduler
 * （REQ-20261121-071002 确认⑥，springdoc 分组 /v3/api-docs/scheduler）+ 工作流分组第五条
 * workflow（REQ-20261121-142316 确认⑧，springdoc 分组 /v3/api-docs/workflow）。
 * 既有 DocGuardFilterTest 覆盖关闭 404/DOC_DISABLED，既有 GatewayRoutingConfigTest 锁定
 * 12 条路由全量 id 集合（不修改两者），本类补充五条 doc 路由的转发形态与 SetPath 一致性
 * （路由配置层断言，不起下游）。
 */
@SpringBootTest(classes = AcmeGatewayApplication.class, properties = {
        // 与 GatewayRoutingConfigTest 同口径：测试域不连 Nacos、不注入真实密钥（14.3.3 测试占位密钥）
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.gateway.user-token.hmac-secret=req-20261114120106-doc-route-test-secret-0123456789",
        "acme.security.token.hmac-secret=req-20261114120106-doc-route-test-secret-0123456789"
})
class REQ20261114120106DocRouteForwardingTest {

    @Autowired
    private RouteDefinitionLocator routeDefinitionLocator;

    @Autowired
    private GatewayDocProperties docProperties;

    @Test
    void docEnabledDefaultsToTrue_when_noExplicitConfig() {
        // AC-1 正向：acme.doc.enabled 缺省绑定 true，文档转发可用（关闭侧 404/DOC_DISABLED 由既有 DocGuardFilterTest 覆盖）
        assertThat(docProperties.isEnabled()).isTrue();
    }

    @Test
    void docRoutesForwardToPlatformServicesWithSetPath() {
        List<RouteDefinition> routes = routeDefinitionLocator.getRouteDefinitions().collectList().block();
        assertThat(routes).isNotNull();

        // 基线三条（REQ-20261114-120106）+ 调度中心分组（REQ-20261121-071002 确认⑥）
        // + 工作流分组（REQ-20261121-142316 确认⑧）
        for (String service : List.of("auth", "system", "infra", "scheduler", "workflow")) {
            String routeId = "platform-" + service + "-doc";
            RouteDefinition doc = routes.stream()
                    .filter(r -> routeId.equals(r.getId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("doc 路由 " + routeId + " 必须存在"));

            // 转发目标：lb://platform-{service}
            assertThat(doc.getUri().toString()).as("%s uri", routeId)
                    .isEqualTo("lb://platform-" + service);

            // 入站路径：/v3/api-docs/{service}（谓词形态以 Path 前缀 + 服务段核对）
            String pathPattern = doc.getPredicates().stream()
                    .filter(p -> "Path".equalsIgnoreCase(p.getName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(routeId + " 缺少 Path 谓词"))
                    .getArgs().values().iterator().next();
            assertThat(pathPattern).as("%s path", routeId).startsWith("/v3/api-docs/");
            assertThat(pathPattern).as("%s path", routeId).contains(service);

            // AC-2：SetPath 后到达服务的路径与服务直连 openapi 端点 /v3/api-docs 一致（三条路由同值，钉死）
            String setPath = doc.getFilters().stream()
                    .filter(f -> "SetPath".equalsIgnoreCase(f.getName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(routeId + " 缺少 SetPath 过滤器"))
                    .getArgs().values().iterator().next();
            assertThat(setPath).as("%s SetPath", routeId).isEqualTo("/v3/api-docs");
        }
    }

    @Test
    void docRouteCountIsExactlyFive() {
        List<RouteDefinition> routes = routeDefinitionLocator.getRouteDefinitions().collectList().block();
        assertThat(routes).isNotNull();
        long docCount = routes.stream()
                .filter(r -> r.getId() != null && r.getId().endsWith("-doc"))
                .count();
        // AMB-3：文档转发路由逐服务、无聚合端点路由；基线 3 条（REQ-20261114-120106）
        // + 调度中心分组 1 条（REQ-20261121-071002 确认⑥）
        // + 工作流分组 1 条（REQ-20261121-142316 确认⑧）= 5 条
        assertThat(docCount).isEqualTo(5);
    }
}
