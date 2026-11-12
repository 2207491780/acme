package com.acme.business.content.application;

/**
 * LIKE 通配符转义（BR-005/BR-012 关键字按字面匹配，防通配注入）：
 * 转义 %、_、\（PostgreSQL LIKE 默认反斜杠转义）。
 */
final class LikePatterns {

    private LikePatterns() {
    }

    /** 将原始关键字转义为字面匹配的 contains 模式（%kw%）。 */
    static String contains(String raw) {
        String escaped = raw.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped.toLowerCase() + "%";
    }
}
