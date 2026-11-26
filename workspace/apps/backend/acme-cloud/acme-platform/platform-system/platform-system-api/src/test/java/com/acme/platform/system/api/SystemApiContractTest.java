package com.acme.platform.system.api;

import com.acme.common.serialization.JsonModules;
import com.acme.platform.system.api.dto.AdminSystemConfigView;
import com.acme.platform.system.api.dto.AdminUserView;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * platform-system-api 契约测试（16.1，本地可执行口径）：
 * DTO 序列化形态稳定（Long→String、枚举 wire 名、时间 ISO-8601）与 Feign 声明元数据锁定（9.1）。
 */
class SystemApiContractTest {

    private final JsonMapper mapper = buildMapper();

    private static JsonMapper buildMapper() {
        JsonMapper.Builder builder = JsonMapper.builder();
        for (JacksonModule module : JsonModules.standard()) {
            builder.addModule(module);
        }
        return builder.build();
    }

    @Test
    void userViewSerializesLongAsStringAndEnumAsName() {
        AdminUserView view = new AdminUserView(1234567890123456789L, "admin", "超级管理员",
                "a@acme.com", "13800000000", 876543210987654321L, 1024L,
                EnableStatus.ENABLED, true, List.of(1L),
                Instant.parse("2026-10-22T08:00:00Z"), Instant.parse("2026-10-22T08:00:00Z"), 0L,
                "http://files.acme.test/avatar.png");
        String json = mapper.writeValueAsString(view);
        // Long→String（openapi 契约：id / roleIds / version 均为 string；deptId/positionId 为
        // REQ-20261114-120104 增补的同型 Long 字段）
        assertThat(json).contains("\"id\":\"1234567890123456789\"");
        assertThat(json).contains("\"roleIds\":[\"1\"]");
        assertThat(json).contains("\"deptId\":\"876543210987654321\"");
        assertThat(json).contains("\"positionId\":\"1024\"");
        assertThat(json).contains("\"version\":\"0\"");
        // 枚举按名（openapi EnableStatus）
        assertThat(json).contains("\"status\":\"ENABLED\"");
        // 时间 ISO-8601
        assertThat(json).contains("\"createdAt\":\"2026-10-22T08:00:00Z\"");
        // avatarUrl 透传（REQ-20261119-082531 确认记录①）
        assertThat(json).contains("\"avatarUrl\":\"http://files.acme.test/avatar.png\"");
        // 回读稳定
        AdminUserView back = mapper.readValue(json, AdminUserView.class);
        assertThat(back.id()).isEqualTo(view.id());
        assertThat(back.roleIds()).containsExactly(1L);
        assertThat(back.deptId()).isEqualTo(view.deptId());
        assertThat(back.positionId()).isEqualTo(view.positionId());
        assertThat(back.status()).isEqualTo(EnableStatus.ENABLED);
        assertThat(back.avatarUrl()).isEqualTo("http://files.acme.test/avatar.png");
    }

    @Test
    void profileClientContractMetadataLocked() throws Exception {
        FeignClient feignClient = AuthProfileClient.class.getAnnotation(FeignClient.class);
        assertThat(feignClient).isNotNull();
        assertThat(feignClient.name()).isEqualTo("platform-system");
        assertThat(feignClient.contextId()).isEqualTo("authProfileClient");

        // REQ-20261125-001738：签名由 findByUsername(username) 改为 (tenantCode, username)，
        // path 与返回形状不变；反射断言不删，改为校验两参数顺序与 @RequestParam 名。
        Method method = AuthProfileClient.class.getMethod("findByUsername", String.class, String.class);
        GetMapping getMapping = method.getAnnotation(GetMapping.class);
        assertThat(getMapping).isNotNull();
        assertThat(getMapping.value()).containsExactly("/internal/v1/auth-profiles");
        Parameter[] parameters = method.getParameters();
        assertThat(parameters).as("查档契约应为 tenantCode + username 两必填参数").hasSize(2);
        assertThat(parameters[0].getAnnotation(RequestParam.class).value()).isEqualTo("tenantCode");
        assertThat(parameters[1].getAnnotation(RequestParam.class).value()).isEqualTo("username");
        assertThat(method.getReturnType()).isEqualTo(ContractResult.class);
    }

    @Test
    void authorizationClientContractMetadataLocked() throws Exception {
        FeignClient feignClient = AuthorizationClient.class.getAnnotation(FeignClient.class);
        assertThat(feignClient).isNotNull();
        assertThat(feignClient.name()).isEqualTo("platform-system");
        assertThat(feignClient.contextId()).isEqualTo("authorizationClient");

        Method method = AuthorizationClient.class.getMethod("hasPermission", String.class);
        GetMapping getMapping = method.getAnnotation(GetMapping.class);
        assertThat(getMapping.value()).containsExactly("/internal/v1/authorization/permissions/{code}");
    }

    @Test
    void dataScopeClientContractMetadataLocked() throws Exception {
        // REQ-20261114-120104：数据范围解析内部契约（Feign 元数据锁定）
        FeignClient feignClient = DataScopeClient.class.getAnnotation(FeignClient.class);
        assertThat(feignClient).isNotNull();
        assertThat(feignClient.name()).isEqualTo("platform-system");
        assertThat(feignClient.contextId()).isEqualTo("dataScopeClient");

        Method method = DataScopeClient.class.getMethod("currentDataScope");
        GetMapping getMapping = method.getAnnotation(GetMapping.class);
        assertThat(getMapping.value()).containsExactly("/internal/v1/authorization/data-scope");
        assertThat(method.getReturnType()).isEqualTo(ContractResult.class);
    }

    @Test
    void dataScopeViewSerializesLongIdsAsStrings() {
        DataScopeView view = new DataScopeView("CUSTOM", List.of(11L, 22L), List.of("42"));
        String json = mapper.writeValueAsString(view);
        assertThat(json).contains("\"scope\":\"CUSTOM\"");
        assertThat(json).contains("\"deptIds\":[\"11\",\"22\"]");
        assertThat(json).contains("\"userIds\":[\"42\"]");
        DataScopeView back = mapper.readValue(json, DataScopeView.class);
        assertThat(back.deptIds()).containsExactly(11L, 22L);
        assertThat(back.userIds()).containsExactly("42");
    }

    @Test
    void systemConfigViewSerializesVersionAsString() {
        // REQ-20261116-230354：缺省视图 wire 形态 { watermarkEnabled:true, captchaEnabled:false,
        // lockIdleTimeoutMs:0, footerCopyright:null, footerVisible:false, watermarkTemplate:null, version:"0" }
        // （captchaEnabled 口径 REQ-20261117-011514；页脚缺省口径 REQ-20261117-025200 确认记录⑤；
        // 水印模板缺省 null=仅用户名，REQ-20261117-180000）
        AdminSystemConfigView view = new AdminSystemConfigView(true, false, 0, null, false, null, 0L);
        String json = mapper.writeValueAsString(view);
        assertThat(json).contains("\"watermarkEnabled\":true");
        assertThat(json).contains("\"captchaEnabled\":false");
        assertThat(json).contains("\"lockIdleTimeoutMs\":0");
        assertThat(json).contains("\"footerCopyright\":null");
        assertThat(json).contains("\"footerVisible\":false");
        assertThat(json).contains("\"watermarkTemplate\":null");
        assertThat(json).contains("\"version\":\"0\"");
        AdminSystemConfigView back = mapper.readValue(json, AdminSystemConfigView.class);
        assertThat(back).isEqualTo(view);
    }

    @Test
    void tenantInternalViewSerializesExpiryIsoAndStatusName() {
        // REQ-20261117-025200：by-code 契约视图 wire 形态（status 枚举名 + expiresAt ISO-8601，可空）
        TenantInternalView view = new TenantInternalView("acme", "Acme 租户", "ENABLED",
                Instant.parse("2027-01-01T00:00:00Z"));
        String json = mapper.writeValueAsString(view);
        assertThat(json).contains("\"tenantCode\":\"acme\"");
        assertThat(json).contains("\"status\":\"ENABLED\"");
        assertThat(json).contains("\"expiresAt\":\"2027-01-01T00:00:00Z\"");
        TenantInternalView back = mapper.readValue(json, TenantInternalView.class);
        assertThat(back).isEqualTo(view);
        // 可空语义：expiresAt=null（长期有效）回读稳定
        TenantInternalView longTerm = new TenantInternalView("acme", "Acme 租户", "ENABLED", null);
        TenantInternalView longTermBack = mapper.readValue(mapper.writeValueAsString(longTerm),
                TenantInternalView.class);
        assertThat(longTermBack.expiresAt()).isNull();
    }

    @Test
    void tenantPublicInternalViewSerializesFooterFields() {
        // REQ-20261117-025200：public-view 契约视图 wire 形态（仅页脚两字段）
        TenantPublicInternalView view = new TenantPublicInternalView("© Acme", true);
        String json = mapper.writeValueAsString(view);
        assertThat(json).contains("\"footerCopyright\":\"© Acme\"");
        assertThat(json).contains("\"footerVisible\":true");
        TenantPublicInternalView back = mapper.readValue(json, TenantPublicInternalView.class);
        assertThat(back).isEqualTo(view);
    }

    @Test
    void tenantClientByCodeContractMetadataLocked() throws Exception {
        // REQ-20261117-025200：租户 by-code 内部契约（Feign 元数据锁定；登录过期校验与切换目标校验共用）
        FeignClient feignClient = TenantClient.class.getAnnotation(FeignClient.class);
        assertThat(feignClient).isNotNull();
        assertThat(feignClient.name()).isEqualTo("platform-system");
        assertThat(feignClient.contextId()).isEqualTo("tenantClient");

        Method method = TenantClient.class.getMethod("findByCode", String.class);
        GetMapping getMapping = method.getAnnotation(GetMapping.class);
        assertThat(getMapping).isNotNull();
        assertThat(getMapping.value()).containsExactly("/internal/v1/tenants/by-code");
        Parameter parameter = method.getParameters()[0];
        assertThat(parameter.getAnnotation(RequestParam.class).value()).isEqualTo("tenantCode");
        assertThat(method.getReturnType()).isEqualTo(ContractResult.class);
    }

    @Test
    void systemConfigClientPublicViewContractMetadataLocked() throws Exception {
        // REQ-20261117-025200：公开站点配置内部契约（Feign 元数据锁定；param 口径同 captcha-policy）
        Method method = SystemConfigClient.class.getMethod("findPublicView", String.class);
        GetMapping getMapping = method.getAnnotation(GetMapping.class);
        assertThat(getMapping).isNotNull();
        assertThat(getMapping.value()).containsExactly("/internal/v1/system-configs/public-view");
        Parameter parameter = method.getParameters()[0];
        assertThat(parameter.getAnnotation(RequestParam.class).value()).isEqualTo("tenantId");
        assertThat(method.getReturnType()).isEqualTo(ContractResult.class);
    }

    @Test
    void authorizationClientBuiltinRolesContractMetadataLocked() throws Exception {
        // REQ-20261117-025200：内置角色实查契约（确认记录④；Feign 元数据锁定）
        Method method = AuthorizationClient.class.getMethod("currentBuiltinRoles");
        GetMapping getMapping = method.getAnnotation(GetMapping.class);
        assertThat(getMapping).isNotNull();
        assertThat(getMapping.value()).containsExactly("/internal/v1/authorization/builtin-roles");
        assertThat(method.getReturnType()).isEqualTo(ContractResult.class);
    }

    @Test
    void captchaPolicyViewSerializesBoolean() {
        // REQ-20261117-011514：验证码策略视图 wire 形态 { captchaEnabled:true|false }
        CaptchaPolicyView enabled = new CaptchaPolicyView(true);
        String json = mapper.writeValueAsString(enabled);
        assertThat(json).contains("\"captchaEnabled\":true");
        CaptchaPolicyView back = mapper.readValue(json, CaptchaPolicyView.class);
        assertThat(back).isEqualTo(enabled);
    }

    @Test
    void systemConfigClientContractMetadataLocked() throws Exception {
        // REQ-20261117-011514：验证码策略内部契约（Feign 元数据锁定；模式同 TenantClient）
        FeignClient feignClient = SystemConfigClient.class.getAnnotation(FeignClient.class);
        assertThat(feignClient).isNotNull();
        assertThat(feignClient.name()).isEqualTo("platform-system");
        assertThat(feignClient.contextId()).isEqualTo("systemConfigClient");

        Method method = SystemConfigClient.class.getMethod("findCaptchaPolicy", String.class);
        GetMapping getMapping = method.getAnnotation(GetMapping.class);
        assertThat(getMapping).isNotNull();
        assertThat(getMapping.value()).containsExactly("/internal/v1/system-configs/captcha-policy");
        Parameter parameter = method.getParameters()[0];
        assertThat(parameter.getAnnotation(RequestParam.class).value()).isEqualTo("tenantId");
        assertThat(method.getReturnType()).isEqualTo(ContractResult.class);
    }

    @Test
    void contractResultDecodesStandardEnvelope() {
        String wire = "{\"success\":true,\"code\":\"0\",\"message\":null,\"data\":{"
                + "\"userId\":\"1\",\"username\":\"admin\",\"nickname\":\"超级管理员\","
                + "\"tenantId\":\"default\",\"status\":\"ENABLED\",\"roles\":[\"super_admin\"]}}";
        ContractResult<AuthProfileView> result = mapper.readValue(wire,
                new TypeReference<ContractResult<AuthProfileView>>() {
                });
        assertThat(result.success()).isTrue();
        assertThat(result.payload().userId()).isEqualTo("1");
        assertThat(result.payload().roles()).containsExactly("super_admin");
        assertThat(result.payload().status()).isEqualTo("ENABLED");
    }

    @Test
    void enumsAndConstantsStable() {
        assertThat(MenuType.valueOf("DIRECTORY")).isEqualTo(MenuType.DIRECTORY);
        assertThat(MenuType.valueOf("MENU")).isEqualTo(MenuType.MENU);
        assertThat(PermissionType.valueOf("API")).isEqualTo(PermissionType.API);
        Set<String> codes = Set.of(SystemErrorCodes.USER_NOT_FOUND, SystemErrorCodes.PERMISSION_DENIED);
        assertThat(codes).allMatch(code -> code.startsWith("platform-system-"));
    }
}
