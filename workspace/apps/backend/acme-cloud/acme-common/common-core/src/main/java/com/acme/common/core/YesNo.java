package com.acme.common.core;

/**
 * 通用枚举：是/否（FR-020；BR-022 仅此两个通用枚举）。枚举值新增兼容、删除/重命名走破坏性变更（BR-015、Backend.md 7.2.2）。
 *
 * <p>未知编码转换口径（DP-007 定稿）：显式失败 IllegalArgumentException，不返回 null、不取默认值。</p>
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public enum YesNo {

    /** 是。 */
    YES(1),

    /** 否。 */
    NO(0);

    private final int code;

    YesNo(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** 编码转换（FR-020）；未知编码显式失败（DP-007 定稿）。 */
    public static YesNo fromCode(int code) {
        for (YesNo value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知编码：" + code);
    }
}
