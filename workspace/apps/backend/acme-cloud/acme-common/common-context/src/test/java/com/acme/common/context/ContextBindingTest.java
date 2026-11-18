package com.acme.common.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 上下文绑定契约级语义测试（AC-005、架构设计 6.2）：bind/get/clear；capture/restore 往返；
 * ContextScope.close() 恢复进入前状态（含异常退出路径；DP-006 本次定稿语义）。
 */
class ContextBindingTest {

    @AfterEach
    void cleanup() {
        ContextSnapshot.clear();
    }

    @Test
    void bindGetClearSemantics() {
        UserContext user = new UserContext("u1", Set.of("admin"));
        UserContext.bind(user);
        assertEquals("u1", UserContext.get().userId());
        assertEquals(Set.of("admin"), UserContext.get().roles());

        UserContext.clear();
        assertNull(UserContext.get());
    }

    @Test
    void snapshotCaptureRestoreRoundTrip() {
        UserContext.bind(new UserContext("u1", Set.of()));
        TenantContext.bind(new TenantContext("t1"));
        ContextSnapshot snapshot = ContextSnapshot.capture();

        UserContext.bind(new UserContext("u2", Set.of()));
        TenantContext.bind(new TenantContext("t2"));
        snapshot.restore();

        assertEquals("u1", UserContext.get().userId());
        assertEquals("t1", TenantContext.get().tenantId());
    }

    @Test
    void scopeCloseClearsContextWhenEntryEmpty() {
        try (ContextScope ignored = ContextScope.open()) {
            UserContext.bind(new UserContext("u1", Set.of()));
            assertEquals("u1", UserContext.get().userId());
        }
        assertNull(UserContext.get());
    }

    @Test
    void scopeCloseClearsContextOnExceptionExit() {
        assertThrows(IllegalStateException.class, () -> {
            try (ContextScope ignored = ContextScope.open()) {
                UserContext.bind(new UserContext("u1", Set.of()));
                throw new IllegalStateException("异常退出");
            }
        });
        assertNull(UserContext.get());
    }

    @Test
    void nestedScopeRestoresOuterContext() {
        UserContext.bind(new UserContext("outer", Set.of()));
        try (ContextScope ignored = ContextScope.open()) {
            UserContext.bind(new UserContext("inner", Set.of()));
            assertEquals("inner", UserContext.get().userId());
        }
        assertEquals("outer", UserContext.get().userId());
    }

    @Test
    void scopeWithSnapshotRestoresEntryStateOnClose() {
        // 构造快照内容 s1
        UserContext.bind(new UserContext("s1", Set.of()));
        ContextSnapshot snapshot = ContextSnapshot.capture();
        // 进入前状态 entry
        UserContext.bind(new UserContext("entry", Set.of()));

        try (ContextScope ignored = ContextScope.open(snapshot)) {
            // 快照已恢复至当前线程
            assertEquals("s1", UserContext.get().userId());
            UserContext.bind(new UserContext("inner", Set.of()));
        }
        // close 恢复进入前状态
        assertEquals("entry", UserContext.get().userId());
    }
}
