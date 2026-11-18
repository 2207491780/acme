package com.acme.bootstrap.local;

import com.acme.platform.system.api.ContractResult;

/**
 * 本地适配器成功信封工厂（REQ-20261118-002130 确认②）：单体本地短路适配器返回与分布式
 * 线上形态同构的 {@link ContractResult}——成功形态对齐 common-core {@code Result.ok}
 * （success=true、code="0"、message=null），消费方 payload()/data() 语义完全一致。
 */
final class LocalContractResults {

    /** 成功码（与 Result.ok 同值，DP-007 定稿）。 */
    private static final String SUCCESS_CODE = "0";

    private LocalContractResults() {
    }

    /** 成功信封（data 可空——与 Result.ok 形态一致）。 */
    static <T> ContractResult<T> ok(T data) {
        return new ContractResult<>(Boolean.TRUE, SUCCESS_CODE, null, data);
    }
}
