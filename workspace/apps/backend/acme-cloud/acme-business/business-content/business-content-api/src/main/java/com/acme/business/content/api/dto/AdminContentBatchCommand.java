package com.acme.business.content.api.dto;

import com.acme.business.content.api.BatchAction;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量命令（openapi AdminContentBatchCommand；FR-010，QD-05 单端点 + action 枚举）。
 * 空 ids 校验错误 400 SYS-01-0001（@NotEmpty）；上界 200（openapi maxItems）。
 */
public record AdminContentBatchCommand(
        @NotNull BatchAction action,
        @NotEmpty @Size(max = 200) List<String> ids) {

    public AdminContentBatchCommand {
        ids = ids == null ? null : List.copyOf(ids);
    }
}
