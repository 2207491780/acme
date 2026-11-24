package com.acme.platform.infra.interfaces;

import com.acme.platform.system.api.SystemPermissions;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日志中心新端点表面锁定（REQ-20261117-061017 §API 契约面；无 Docker 可跑的注解级断言）：
 * 路径 + 权限点标注口径锁定（HTTP 行为由 Testcontainers 集成测试承担，无 Docker 跳过时
 * 本类兜底锁定「端点存在 + 权限点正确」的契约面，防止静默漂移）。
 */
class LogCenterApiSurfaceTest {

    // ---------- 异常日志三端点（GET /admin/v1/infra/op-exceptions[/...]） ----------

    @Test
    void opExceptionControllerShouldLockPathsAndPermission_when_endpointsDeclared() throws Exception {
        RequestMapping mapping = OpExceptionController.class.getAnnotation(RequestMapping.class);
        assertThat(mapping).as("类级 @RequestMapping 路径").isNotNull();
        assertThat(mapping.value()).containsExactly("/admin/v1/infra/op-exceptions");

        Method page = declaredGet(OpExceptionController.class, "page");
        assertThat(page.getAnnotation(GetMapping.class)).as("page 应为 @GetMapping（路径随类级映射）").isNotNull();
        assertThat(permissionOf(page)).isEqualTo(SystemPermissions.INFRA_EXCEPTION_LIST);

        Method detail = declaredGet(OpExceptionController.class, "detail");
        assertThat(getValue(detail)).containsExactly("/{exceptionId}");
        assertThat(permissionOf(detail)).isEqualTo(SystemPermissions.INFRA_EXCEPTION_LIST);

        Method export = declaredGet(OpExceptionController.class, "export");
        assertThat(getValue(export)).containsExactly("/export");
        assertThat(permissionOf(export)).isEqualTo(SystemPermissions.INFRA_EXCEPTION_LIST);
    }

    // ---------- 审计导出端点（GET /admin/v1/infra/audit-events/export） ----------

    @Test
    void auditExportShouldLockPathAndListPermission_when_endpointDeclared() throws Exception {
        RequestMapping mapping = AuditEventController.class.getAnnotation(RequestMapping.class);
        assertThat(mapping.value()).containsExactly("/admin/v1/infra/audit-events");

        Method export = AuditEventController.class.getMethod("export",
                jakarta.servlet.http.HttpServletResponse.class, String.class, String.class,
                String.class, java.time.Instant.class, java.time.Instant.class);
        assertThat(export.getAnnotation(GetMapping.class)).as("audit export 应为 @GetMapping").isNotNull();
        assertThat(getValue(export)).containsExactly("/export");
        assertThat(permissionOf(export)).isEqualTo(SystemPermissions.INFRA_AUDIT_LIST);
    }

    // ---------- 支撑 ----------

    /** 类内按名取 @GetMapping 方法（强制端点存在，缺失即 NoSuchMethodException 失败）。 */
    private static Method declaredGet(Class<?> controller, String name) throws NoSuchMethodException {
        for (Method candidate : controller.getDeclaredMethods()) {
            if (candidate.getAnnotation(GetMapping.class) != null && candidate.getName().equals(name)) {
                return candidate;
            }
        }
        throw new NoSuchMethodException(
                "expected @GetMapping method '" + name + "' on " + controller.getSimpleName());
    }

    private static String[] getValue(Method method) {
        return method.getAnnotation(GetMapping.class).value();
    }

    private static String permissionOf(Method method) {
        var annotation = method.getAnnotation(com.acme.platform.infra.security.RequirePermission.class);
        return annotation == null ? null : annotation.value();
    }
}
