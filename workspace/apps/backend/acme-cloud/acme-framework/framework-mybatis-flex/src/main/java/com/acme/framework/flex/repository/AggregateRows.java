package com.acme.framework.flex.repository;

import java.util.Map;

/**
 * 聚合查询行取值（S14 收敛 auth 登录日志统计与 infra 审计分布两处同款私有工具）：
 * mapper 自定义聚合 SQL 返回 {@code Map<String, Object>} 行，PG 未加引号别名折叠为小写，
 * 统一忽略大小写取键防分歧（列别名约定 stat_*，见各 mapper XML/@Select）。
 */
public final class AggregateRows {

    private AggregateRows() {
    }

    /** 忽略大小写取字符串列（别名折叠防分歧）。 */
    public static String stringValue(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)
                    && entry.getValue() != null) {
                return String.valueOf(entry.getValue());
            }
        }
        return null;
    }

    /** 忽略大小写取数值列（PG bigint → Long）。 */
    public static long numberValue(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)
                    && entry.getValue() instanceof Number number) {
                return number.longValue();
            }
        }
        return 0L;
    }
}
