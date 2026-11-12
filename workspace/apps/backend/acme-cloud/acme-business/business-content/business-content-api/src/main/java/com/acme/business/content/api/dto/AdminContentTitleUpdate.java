package com.acme.business.content.api.dto;

import jakarta.validation.constraints.Size;

/**
 * 行内改题请求（openapi AdminContentTitleUpdate；FR-008）。
 * title 非空校验在领域层（BR-003 → business-content-01-0002），此处仅长度约束（SYS-01-0001）。
 *
 * @param expectedVersion 期望版本（QD-04：可选，UI 恒传；携带则乐观锁校验，冲突 SYS-01-0003；
 *                        Long 反序列化接受字符串与数值两种输入，common-serialization 口径）
 */
public record AdminContentTitleUpdate(
        @Size(max = 200) String title,
        Long expectedVersion) {
}
