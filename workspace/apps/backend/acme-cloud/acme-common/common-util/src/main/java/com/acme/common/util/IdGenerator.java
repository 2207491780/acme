package com.acme.common.util;

/**
 * ID 生成能力契约（FR-004）。全局唯一方案（雪花/号段）口径见 Backend.md 12.3.1、BR-021，不得依赖数据库自增。
 *
 * <p>失败策略（DP-003 定稿）：生成失败显式失败（不得返回重复或非法 ID）；雪花实现见
 * {@link SnowflakeIdGenerator}（时钟回拨与 worker 分配策略见其类级 javadoc）。</p>
 *
 * <p>号段分配器（依赖存储协作）暂不建类、不定义 SPI（Q-002 方案 A）；引入时以新增实现方式扩展本契约。</p>
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public interface IdGenerator {

    /** 生成全局唯一 ID（FR-004）；失败显式失败，不得返回重复或非法 ID。 */
    long nextId();
}
