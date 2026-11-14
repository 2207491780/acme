package com.acme.platform.infra.api;

/**
 * 基础设施域错误码文本常量（Backend.md 10.5.2，serviceId=platform-infra）：
 * 完整枚举（含 HTTP 映射）归 platform-infra-service InfraError，两处取值一致由单测锁定。
 *
 * <p>REQ-20261114-071607 增补六码（文件服务 / Excel demo / 任务调度）：码值沿用契约 §API
 * 「错误码在此列全」的符号字面量（FILE_SIZE_EXCEEDED 等），与三段式文本常量并存口径见契约
 * API 段注记；i18n 消息键 = 错误码（NFR-007）。</p>
 */
public final class InfraErrorCodes {

    /** 审计事件无效（400：协议字段缺失/非法，消费侧毒消息转死信）。 */
    public static final String AUDIT_EVENT_INVALID = "platform-infra-01-0001";

    /** 审计存储失败（500：落库异常，重试后转死信）。 */
    public static final String AUDIT_STORE_FAILED = "platform-infra-01-0002";

    /** 文件大小超出上传上限（400：acme.storage.upload.max-size，REQ-20261114-071607）。 */
    public static final String FILE_SIZE_EXCEEDED = "FILE_SIZE_EXCEEDED";

    /** 文件类型不在白名单（400：acme.storage.upload.allowed-content-types，REQ-20261114-071607）。 */
    public static final String FILE_TYPE_REJECTED = "FILE_TYPE_REJECTED";

    /** 文件不存在（404：元数据/下载/删除目标缺失，REQ-20261114-071607）。 */
    public static final String FILE_NOT_FOUND = "FILE_NOT_FOUND";

    /** Excel 文件无法解析（400：demo-import 文件非法，REQ-20261114-071607）。 */
    public static final String EXCEL_PARSE_FAILED = "EXCEL_PARSE_FAILED";

    /** 任务执行中（409：ShedLock 互斥下重复手动触发，不排队，REQ-20261114-071607）。 */
    public static final String JOB_ALREADY_RUNNING = "JOB_ALREADY_RUNNING";

    /** 任务不存在（404：jobKey 未注册，REQ-20261114-071607）。 */
    public static final String JOB_NOT_FOUND = "JOB_NOT_FOUND";

    private InfraErrorCodes() {
    }
}
