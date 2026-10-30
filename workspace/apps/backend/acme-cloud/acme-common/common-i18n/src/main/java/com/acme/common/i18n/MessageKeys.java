package com.acme.common.i18n;

/**
 * 消息资源键约定（FR-028）：按约定拼接消息键（段间 {@code .} 连接）。
 * 键命名规则（DP-012 定稿，p5-optional-modules D-005）：消息键 = 语义段经 {@link #of} 段间点连接，
 * 或直接以错误码作键（如 {@code SYS-01-0001}）；两类形态可混用，键全局稳定、废弃不复用。
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public final class MessageKeys {

    private MessageKeys() {
    }

    /**
     * 按约定拼接消息键（FR-028）：段间 {@code .} 连接；段空白显式失败。
     */
    public static String of(String... segments) {
        if (segments == null || segments.length == 0) {
            throw new IllegalArgumentException("segments 不能为空（DP-012）");
        }
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment == null || segment.isBlank()) {
                throw new IllegalArgumentException("键段不能为空（DP-012）");
            }
            if (i > 0) {
                key.append('.');
            }
            key.append(segment);
        }
        return key.toString();
    }
}
