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
                Instant.parse("2026-10-22T08:00:00Z"), Instant.parse("2026-10-22T08:00:00Z"), 0L);
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
        // 回读稳定
        AdminUserView back = mapper.readValue(json, AdminUserView.class);
        assertThat(back.id()).isEqualTo(view.id());
        assertThat(back.roleIds()).containsExactly(1L);
        assertThat(back.deptId()).isEqualTo(view.deptId());
        assertThat(back.positionId()).isEqualTo(view.positionId());
        assertThat(back.status()).isEqualTo(EnableStatus.ENABLED);
    }

    @Test
    void profileClientContractMetadataLocked() throws Exception {
        FeignClient feignClient = AuthProfileClient.class.getAnnotation(FeignClient.class);
        assertThat(feignClient).isNotNull();
        assertThat(feignClient.name()).isEqualTo("platform-system");
        assertThat(feignClient.contextId()).isEqualTo("authProfileClient");

        Method method = AuthProfileClient.class.getMethod("findByUsername", String.class);
        GetMapping getMapping = method.getAnnotation(GetMapping.class);
        assertThat(getMapping).isNotNull();
        assertThat(getMapping.value()).containsExactly("/internal/v1/auth-profiles");
        Parameter parameter = method.getParameters()[0];
        assertThat(parameter.getAnnotation(RequestParam.class).value()).isEqualTo("username");
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
        // lockIdleTimeoutMs:0, version:"0" }（captchaEnabled 口径 REQ-20261117-011514）
        AdminSystemConfigView view = new AdminSystemConfigView(true, false, 0, 0L);
        String json = mapper.writeValueAsString(view);
        assertThat(json).contains("\"watermarkEnabled\":true");
        assertThat(json).contains("\"captchaEnabled\":false");
        assertThat(json).contains("\"lockIdleTimeoutMs\":0");
        assertThat(json).contains("\"version\":\"0\"");
        AdminSystemConfigView back = mapper.readValue(json, AdminSystemConfigView.class);
        assertThat(back).isEqualTo(view);
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
