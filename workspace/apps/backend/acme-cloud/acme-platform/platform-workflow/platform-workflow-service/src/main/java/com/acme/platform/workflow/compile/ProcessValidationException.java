package com.acme.platform.workflow.compile;

import java.util.List;

/**
 * 流程结构校验异常（部署前置校验失败 → WORKFLOW_VALIDATION_FAILED 409，逐条错误随信封返回）。
 */
public final class ProcessValidationException extends RuntimeException {

    private final List<ValidationError> errors;

    public ProcessValidationException(List<ValidationError> errors) {
        super("流程结构校验未通过：" + errors.size() + " 条错误");
        this.errors = List.copyOf(errors);
    }

    public List<ValidationError> getErrors() {
        return errors;
    }
}
