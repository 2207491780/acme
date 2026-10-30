package com.acme.business.content.api.dto;

import java.util.List;

/**
 * 批量结果（openapi AdminContentBatchResultView；逐条、不整批回滚，FR-010）。
 * 幂等口径（架构设计 6.2）：succeededIds 含幂等 no-op（已删行 / 已达目标态）。
 * total 为 Long 计数（wire 字符串化，平台 Long→String 约定）；succeededIds / failures.id 为请求 token 回显（String）。
 */
public record AdminContentBatchResultView(
        Long total,
        List<String> succeededIds,
        List<AdminBatchFailureView> failures) {

    public AdminContentBatchResultView {
        succeededIds = succeededIds == null ? List.of() : List.copyOf(succeededIds);
        failures = failures == null ? List.of() : List.copyOf(failures);
    }
}
