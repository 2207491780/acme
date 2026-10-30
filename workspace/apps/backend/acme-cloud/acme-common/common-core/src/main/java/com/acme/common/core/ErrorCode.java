package com.acme.common.core;

/**
 * 错误码抽象（FR-015；Q-007 结论：interface 抽象，各服务枚举实现）。
 *
 * <p>错误码全局唯一、废弃不复用（BR-017、Backend.md 10.5.2）；结构见 common-contract.ErrorCodeLayout。</p>
 *
 * @implNote 暂缓 DP-007：错误码缺省、重复登记的发现机制待定稿。
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public interface ErrorCode {

    /**
     * 全局唯一错误码（FR-015）；结构见 common-contract.ErrorCodeLayout（Backend.md 10.5.2）。
     */
    String code();

    /**
     * 面向调用方的默认提示（FR-015；Backend.md 10.5.1）。
     */
    String defaultMessage();
}
