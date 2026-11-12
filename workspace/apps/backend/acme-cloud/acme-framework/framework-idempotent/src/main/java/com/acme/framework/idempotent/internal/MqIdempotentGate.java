package com.acme.framework.idempotent.internal;

import com.acme.framework.stream.support.IdempotentGuard;

/**
 * MQ 入口幂等门（FR-002/AC-003、BR-007）：三态方法全部委托 framework-stream {@link IdempotentGuard}
 * （support 公开面，D-003 默认直接复用不重构）；无第二套去重实现。
 */
public class MqIdempotentGate {

    private final IdempotentGuard guard;

    public MqIdempotentGate(IdempotentGuard guard) {
        this.guard = guard;
    }

    /** 认领（首投 STARTED；重复/在途跳过）。 */
    public IdempotentGuard.Begin tryBegin(String idempotentKey) {
        return guard.tryBegin(idempotentKey);
    }

    /** 成功标记（已完成去重窗口按 stream 既有 TTL 口径）。 */
    public void markDone(String idempotentKey) {
        guard.markDone(idempotentKey);
    }

    /** 失败释放（允许 binder 重投，10.3.3）。 */
    public void markFailed(String idempotentKey) {
        guard.markFailed(idempotentKey);
    }
}
