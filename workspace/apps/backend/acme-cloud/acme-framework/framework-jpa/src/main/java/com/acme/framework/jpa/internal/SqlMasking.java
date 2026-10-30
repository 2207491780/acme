package com.acme.framework.jpa.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 绑定参数脱敏（FR-013、14.1.1）：字段名启发式（password/secret/token/phone/idcard 等命中即掩码）；
 * 列清单可对齐时按列定位掩码，否则保守全掩（BR-020 不泄露敏感信息）。
 */
public final class SqlMasking {

    private static final List<String> SENSITIVE_WORDS = List.of("password", "secret", "token", "phone", "idcard");
    private static final Pattern INSERT_COLUMNS =
            Pattern.compile("insert\\s+into\\s+\\w+\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE_COLUMNS =
            Pattern.compile("update\\s+\\w+\\s+set\\s+(.*?)(\\s+where\\s|$)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    private SqlMasking() {
    }

    /** 渲染绑定参数列表（脱敏后）。 */
    public static List<String> render(String sql, List<Object> params) {
        List<String> columns = columnsOf(sql);
        List<String> rendered = new ArrayList<>(params.size());
        boolean anySensitiveSql = containsSensitiveWord(sql);
        for (int i = 0; i < params.size(); i++) {
            boolean masked = columns.isEmpty()
                    ? anySensitiveSql
                    : i < columns.size() && isSensitive(columns.get(i));
            String text = params.get(i) == null ? "null" : String.valueOf(params.get(i));
            rendered.add(masked ? "******" : text);
        }
        return rendered;
    }

    /** SQL 是否含敏感字段词（启发式）。 */
    public static boolean containsSensitiveWord(String sql) {
        String lower = sql.toLowerCase(Locale.ROOT);
        for (String word : SENSITIVE_WORDS) {
            if (lower.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSensitive(String column) {
        String lower = column.toLowerCase(Locale.ROOT);
        for (String word : SENSITIVE_WORDS) {
            if (lower.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> columnsOf(String sql) {
        Matcher insert = INSERT_COLUMNS.matcher(sql);
        if (insert.find()) {
            return identifiers(insert.group(1));
        }
        Matcher update = UPDATE_COLUMNS.matcher(sql);
        if (update.find()) {
            List<String> names = new ArrayList<>();
            for (String assignment : update.group(1).split(",")) {
                int eq = assignment.indexOf('=');
                if (eq > 0) {
                    String name = assignment.substring(0, eq).trim();
                    names.add(name.replace("\"", ""));
                }
            }
            return names;
        }
        return List.of();
    }

    private static List<String> identifiers(String text) {
        List<String> names = new ArrayList<>();
        Matcher matcher = IDENTIFIER.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group());
        }
        return names;
    }
}
