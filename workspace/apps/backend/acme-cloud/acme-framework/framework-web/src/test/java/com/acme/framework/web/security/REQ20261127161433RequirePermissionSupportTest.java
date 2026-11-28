package com.acme.framework.web.security;

import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权限拦截器骨架（REQ-20261127-161433 P2-19/P3-08）：注解解析（方法级优先、类级兜底）与
 * 决策委托——**类级生效测试**（P3-08 二选一取「补测试」：@Target 统一 {METHOD, TYPE} 后
 * 类级标注合法且生效，骨架的类型级兜底分支由本类锁死）。
 */
class REQ20261127161433RequirePermissionSupportTest {

    /** 决策记录缝。 */
    static final class RecordingDecider implements PermissionDecider {

        final List<String> required = new ArrayList<>();

        @Override
        public void requirePermission(String permissionCode) {
            required.add(permissionCode);
        }
    }

    @RequirePermission("system:user:create")
    static class TypeAnnotatedController {

        public void ok() {
        }
    }

    static class MethodAnnotatedController {

        @RequirePermission("system:user:list")
        public void list() {
        }
    }

    static class PlainController {

        public void open() {
        }
    }

    private static HandlerMethod handlerOf(Object controller, String method) throws Exception {
        return new HandlerMethod(controller, controller.getClass().getMethod(method));
    }

    @Test
    void requires_permission_when_annotation_on_type() throws Exception {
        RecordingDecider decider = new RecordingDecider();
        RequirePermissionInterceptorSupport support = new RequirePermissionInterceptorSupport(decider) { };

        support.preHandle(null, null, handlerOf(new TypeAnnotatedController(), "ok"));

        assertThat(decider.required).containsExactly("system:user:create");
    }

    @Test
    void requires_permission_when_annotation_on_method() throws Exception {
        RecordingDecider decider = new RecordingDecider();
        RequirePermissionInterceptorSupport support = new RequirePermissionInterceptorSupport(decider) { };

        support.preHandle(null, null, handlerOf(new MethodAnnotatedController(), "list"));

        assertThat(decider.required).containsExactly("system:user:list");
    }

    @Test
    void passes_through_when_annotation_absent() throws Exception {
        RecordingDecider decider = new RecordingDecider();
        RequirePermissionInterceptorSupport support = new RequirePermissionInterceptorSupport(decider) { };

        boolean allowed = support.preHandle(null, null, handlerOf(new PlainController(), "open"));

        assertThat(allowed).isTrue();
        assertThat(decider.required).isEmpty();
    }

    @Test
    void passes_through_for_non_handler_method() {
        RecordingDecider decider = new RecordingDecider();
        RequirePermissionInterceptorSupport support = new RequirePermissionInterceptorSupport(decider) { };

        boolean allowed = support.preHandle(null, null, new Object());

        assertThat(allowed).isTrue();
        assertThat(decider.required).isEmpty();
    }
}
