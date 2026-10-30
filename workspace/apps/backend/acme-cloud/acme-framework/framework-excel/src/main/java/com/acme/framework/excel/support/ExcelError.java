package com.acme.framework.excel.support;

import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.HttpStatusAware;

/**
 * framework-excel 错误码枚举（SYS-12 段，序号 0001 起，DP-008 段位锁定）。
 */
public enum ExcelError implements ErrorCode, HttpStatusAware {

    /** 映射声明非法/缺失（FR-004：不产出半截文件）。 */
    MAPPING_INVALID("SYS-12-0001", "Excel 字段映射声明非法", 500),

    /** 文件损坏/格式不符（FR-005）。 */
    FILE_INVALID("SYS-12-0002", "Excel 文件损坏或格式不符", 400),

    /** 行数据非法（FAIL_FAST 口径抛出）。 */
    ROW_INVALID("SYS-12-0003", "Excel 行数据非法", 400),

    /** 行数超出限制（FR-004/005）。 */
    ROW_LIMIT_EXCEEDED("SYS-12-0004", "Excel 行数超出限制", 400);

    private final String code;
    private final String defaultMessage;
    private final int httpStatus;

    ExcelError(String code, String defaultMessage, int httpStatus) {
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
