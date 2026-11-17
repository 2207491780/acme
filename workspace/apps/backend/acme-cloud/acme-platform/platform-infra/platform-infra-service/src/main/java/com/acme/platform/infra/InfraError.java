package com.acme.platform.infra;

import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.HttpStatusAware;
import com.acme.platform.infra.api.InfraErrorCodes;

/**
 * 基础设施域错误码枚举（10.5.2 三段式，serviceId=platform-infra）。
 * 码值与 platform-infra-api InfraErrorCodes 常量一一对应。
 * REQ-20261114-071607 增补六码：文件服务（02 段语义组：file/excel/job 接线），
 * 码值为契约 §API 符号字面量（FILE_SIZE_EXCEEDED 等），HTTP 语义随错误码登记。
 */
public enum InfraError implements ErrorCode, HttpStatusAware {

    /** 审计事件无效（协议字段缺失/非法；消费侧毒消息重试后转死信）。 */
    AUDIT_EVENT_INVALID(InfraErrorCodes.AUDIT_EVENT_INVALID, "审计事件无效", 400),

    /** 审计存储失败（落库异常；重试后转死信）。 */
    AUDIT_STORE_FAILED(InfraErrorCodes.AUDIT_STORE_FAILED, "审计存储失败", 500),

    /** 文件大小超出上传上限（acme.storage.upload.max-size）。 */
    FILE_SIZE_EXCEEDED(InfraErrorCodes.FILE_SIZE_EXCEEDED, "文件大小超出上限", 400),

    /** 文件类型不在白名单（acme.storage.upload.allowed-content-types）。 */
    FILE_TYPE_REJECTED(InfraErrorCodes.FILE_TYPE_REJECTED, "文件类型不在白名单", 400),

    /** 文件不存在（元数据/下载/删除目标缺失）。 */
    FILE_NOT_FOUND(InfraErrorCodes.FILE_NOT_FOUND, "文件不存在", 404),

    /** Excel 文件无法解析（demo-import 文件非法）。 */
    EXCEL_PARSE_FAILED(InfraErrorCodes.EXCEL_PARSE_FAILED, "Excel 文件解析失败", 400),

    /** 任务执行中（ShedLock 互斥下重复手动触发，不排队）。 */
    JOB_ALREADY_RUNNING(InfraErrorCodes.JOB_ALREADY_RUNNING, "任务正在执行", 409),

    /** 任务不存在（jobKey 未注册）。 */
    JOB_NOT_FOUND(InfraErrorCodes.JOB_NOT_FOUND, "任务不存在", 404),

    /** 租户存储配额超出（413；REQ-20261117-045754 确认③，并发上传下为近似护栏）。 */
    STORAGE_QUOTA_EXCEEDED(InfraErrorCodes.STORAGE_QUOTA_EXCEEDED, "租户存储配额已超出", 413),

    /** 图片文件无效（不可解码或像素超上限，400；REQ-20261117-045754 确认④）。 */
    IMAGE_INVALID(InfraErrorCodes.IMAGE_INVALID, "图片文件无效或超出像素上限", 400),

    /** 直传票据无效（不存在/过期/已消费/租户或操作者不符，400；REQ-20261117-045754 确认⑥）。 */
    TICKET_INVALID(InfraErrorCodes.TICKET_INVALID, "直传票据无效或已失效", 400),

    /** 直传完成对象大小与登记不一致（容差 0，400；REQ-20261117-045754 确认⑥）。 */
    SIZE_MISMATCH(InfraErrorCodes.SIZE_MISMATCH, "对象大小与直传登记不一致", 400),

    /** 直传完成对象缺失（404；REQ-20261117-045754 §API STORAGE_OBJECT_MISSING 语义）。 */
    STORAGE_OBJECT_MISSING(InfraErrorCodes.STORAGE_OBJECT_MISSING, "直传对象缺失", 404),

    /** 运行异常日志不存在（404：详情目标缺失；REQ-20261117-061017）。 */
    OP_EXCEPTION_NOT_FOUND(InfraErrorCodes.OP_EXCEPTION_NOT_FOUND, "异常日志不存在", 404);

    private final String code;
    private final String defaultMessage;
    private final int httpStatus;

    InfraError(String code, String defaultMessage, int httpStatus) {
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
