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
    BUCKET_UNAVAILABLE("SYS-15-0005", "对象存储桶不可用", 500),

    /** 本地盘根目录配置缺失（type=local 必配；REQ-20261114-071607）。 */
    LOCAL_ROOT_DIR_MISSING("SYS-15-0006", "本地存储根目录配置缺失", 500),

    /** 租户存储配额超出（413；REQ-20261117-045754 确认③，并发上传下为近似护栏）。 */
    QUOTA_EXCEEDED("SYS-15-0007", "租户存储配额已超出", 413),

    /** 图片文件无效（不可解码或像素超上限，400；REQ-20261117-045754 确认④）。 */
    IMAGE_INVALID("SYS-15-0008", "图片文件无效或超出像素上限", 400),

    /** 直传票据无效（不存在/过期/已消费/租户或操作者不符，400；REQ-20261117-045754 确认⑥）。 */
    TICKET_INVALID("SYS-15-0009", "直传票据无效或已失效", 400),

    /** 直传完成对象大小与登记不一致（容差 0，400；REQ-20261117-045754 确认⑥）。 */
    SIZE_MISMATCH("SYS-15-0010", "对象大小与直传登记不一致", 400),

    /** 当前适配器不支持预签名直传（LOCAL 无预签名语义，501；REQ-20261117-045754）。 */
    PRESIGN_UNSUPPORTED("SYS-15-0011", "当前存储类型不支持预签名直传", 501);

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
