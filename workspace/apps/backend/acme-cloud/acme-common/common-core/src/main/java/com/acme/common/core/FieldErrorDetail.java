package com.acme.common.core;

/**
 * 校验错误字段级明细（Q-D05：Result 扩展携带明细的载体，与 {@link Result} 同域）。
 *
 * <p>仅承载字段名与面向调用方的提示，不携带堆栈/SQL 等实现细节（Backend.md 10.5.4）。</p>
 */
public record FieldErrorDetail(String field, String message) {
}
