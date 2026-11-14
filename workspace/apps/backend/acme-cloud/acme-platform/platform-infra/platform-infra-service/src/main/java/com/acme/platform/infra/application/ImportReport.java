package com.acme.platform.infra.application;

import java.util.List;

/**
 * Excel demo 导入报告（REQ-20261114-071607 §API ImportReport：total/valid/errors[{row,field,message}]）；
 * validate-only 语义（不落库），行号 = 数据行号自 1 起（表头跳过，framework-excel 回路口径）。
 */
public record ImportReport(int total, long valid, List<RowError> errors) {

    public ImportReport {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    /** 行级错误明细（row=数据行号 1 起；field=字段名（DemoRow 字段口径，裁决⑦）；message=校验提示）。 */
    public record RowError(int row, String field, String message) {
    }
}
