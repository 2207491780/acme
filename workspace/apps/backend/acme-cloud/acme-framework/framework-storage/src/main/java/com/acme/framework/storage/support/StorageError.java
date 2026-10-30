package com.acme.framework.storage.support;

import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.HttpStatusAware;

/**
 * framework-storage 错误码枚举（SYS-15 段，序号 0001 起，DP-008 段位锁定；架构设计 9.2）。
 */
public enum StorageError implements ErrorCode, HttpStatusAware {

    /** 对象不存在（404）。 */
    OBJECT_NOT_FOUND("SYS-15-0001", "对象不存在", 404),

    /** 凭证非法。 */
    CREDENTIAL_INVALID("SYS-15-0002", "对象存储凭证非法", 500),

    /** 凭证配置缺失（启动/首次使用显式失败，不静默空转）。 */
    CREDENTIAL_MISSING("SYS-15-0003", "对象存储凭证配置缺失", 500),

    /** 网络失败。 */
    NETWORK_FAILURE("SYS-15-0004", "对象存储访问失败", 502),

    /** 桶不可用。 */
    BUCKET_UNAVAILABLE("SYS-15-0005", "对象存储桶不可用", 500);

    private final String code;
    private final String defaultMessage;
    private final int httpStatus;

    StorageError(String code, String defaultMessage, int httpStatus) {
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
