package com.acme.framework.tx.support;

import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.HttpStatusAware;

/**
 * framework-tx 错误码枚举（SYS-19 段，序号 0001 起，DP-008 段位锁定）。
 */
public enum TxError implements ErrorCode, HttpStatusAware {

    /** 事务装配配置非法（FR-022、架构设计 13.3）。 */
    TX_CONFIG_INVALID("SYS-19-0001", "分布式事务配置非法", 500),

    /** 分布式事务基础设施缺失（架构设计 13.3：不得静默降级）。 */
    SEATA_MISSING("SYS-19-0002", "分布式事务基础设施缺失", 500);

    private final String code;
    private final String defaultMessage;
    private final int httpStatus;

    TxError(String code, String defaultMessage, int httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
