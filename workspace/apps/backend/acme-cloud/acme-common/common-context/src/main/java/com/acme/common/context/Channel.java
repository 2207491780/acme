package com.acme.common.context;

/**
 * B/C 端标识（FR-011；Q-006 结论：{@code Channel} 枚举，取值 {@code B}/{@code C}）。
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public enum Channel {

    /** B 端（管理端）。 */
    B,

    /** C 端（用户端）。 */
    C
}
