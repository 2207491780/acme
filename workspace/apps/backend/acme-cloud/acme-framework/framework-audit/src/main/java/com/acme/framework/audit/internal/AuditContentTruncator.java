package com.acme.framework.audit.internal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 审计内容截取与脱敏（FR-010/AC-010、BR-013：不含敏感信息、堆栈、SQL、内部地址）。
 * 敏感键取对等文本掩码口径（架构设计 8.3：与 common-serialization DesensitizeStrategy 掩码语义对等，
 * 不引序列化依赖，避免 audit 依赖面膨胀）。
 *
 * <p>REQ-20261127-161433 P2-21：{@code before} 旧值快照为 JSON 形态——新增 {@link #maskJson}
 * 递归脱敏（Jackson 树遍历，任意嵌套层的敏感键值掩码；解析失败 fail-open 保持原文）。
 * Jackson 依赖已在切面序列化路径存在（{@code AuditAspect} 直用 {@code JsonMapper}），零依赖面扩张。</p>
 */
public final class AuditContentTruncator {

    /** 敏感键模式（password/secret/token/key 等）。 */
    private static final Pattern SENSITIVE = Pattern.compile(
            ".*((?i)password|passwd|secret|token|credential|api[-_]?key|access[-_]?key).*");

    /** 掩码占位。 */
    private static final String MASK = "***";

    private AuditContentTruncator() {
    }

    /**
     * 截取到上限并对敏感键掩码。
     *
     * @param content     内容摘要（{@code key=value} 形态）
     * @param maxLength   截取上限
     * @param maskEnabled 是否脱敏
     */
    public static String apply(String content, int maxLength, boolean maskEnabled) {
        if (content == null) {
            return null;
        }
        String masked = maskEnabled ? mask(content) : content;
        int limit = Math.max(maxLength, 0);
        return masked.length() <= limit ? masked : masked.substring(0, limit);
    }

    /** 对 {@code key=value} 段内敏感键值掩码（对等文本掩码，8.3）。 */
    static String mask(String content) {
        StringBuilder builder = new StringBuilder(content.length());
        for (String part : content.split(",\\s*")) {
            int eq = part.indexOf('=');
            if (eq > 0 && SENSITIVE.matcher(part.substring(0, eq)).matches()) {
                builder.append(part, 0, eq).append('=').append(MASK);
            } else {
                builder.append(part);
            }
            builder.append(", ");
        }
        return builder.length() > 2 ? builder.substring(0, builder.length() - 2) : content;
    }

    /**
     * before 旧值 JSON 递归脱敏（P2-21）：任意嵌套层的敏感键值统一掩码（对象/数组容器递归）；
     * 非 JSON 形态或解析失败 fail-open 保持原文（文本掩码口径在调用面兜底）。
     *
     * @param json   before 旧值序列化产物（JSON 对象/数组形态）
     * @param mapper 切面既有 JsonMapper（null 时原样返回）
     */
    public static String maskJson(String json, JsonMapper mapper) {
        if (json == null || mapper == null || !(json.startsWith("{") || json.startsWith("["))) {
            return json;
        }
        try {
            JsonNode root = mapper.readTree(json);
            maskNode(root);
            return mapper.writeValueAsString(root);
        } catch (RuntimeException ex) {
            return json;
        }
    }

    /** 递归掩码：对象敏感键 → 掩码占位；容器（对象/数组）下层递归。 */
    private static void maskNode(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> sensitive = new ArrayList<>();
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                if (isSensitiveKey(field.getKey())) {
                    sensitive.add(field.getKey());
                } else {
                    maskNode(field.getValue());
                }
            }
            for (String key : sensitive) {
                object.set(key, JsonNodeFactory.instance.textNode(MASK));
            }
        } else if (node instanceof ArrayNode array) {
            for (JsonNode child : array) {
                maskNode(child);
            }
        }
    }

    /** 键名匹配（供测试与切面复用）。 */
    public static boolean isSensitiveKey(String key) {
        return key != null && SENSITIVE.matcher(key.toLowerCase(Locale.ROOT)).matches();
    }
}
