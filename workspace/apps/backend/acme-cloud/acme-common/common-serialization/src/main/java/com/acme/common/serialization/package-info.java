/**
 * common-serialization 对外 Jackson 3 标准 Module 集与 Json 门面（FR-024–FR-027；Modules.md 2.1、2.5）。
 *
 * <p>约束：不得引入 jackson-datatype-jsr310 / jdk8 / parameter-names，不得 new ObjectMapper()/copy()（BR-012）；
 * 本模块对 common-core 的引用仅限 Json 门面异常转换（BR-002、Modules.md 2.5.3）。</p>
 *
 * <p>暂缓项索引：</p>
 * <ul>
 *   <li>DP-010：枚举 code Module、BigDecimal 规整完整实现；新增脱敏策略与策略缺失口径；</li>
 *   <li>DP-011：Json 门面完整方法集与异常转换行为测试；</li>
 *   <li>DP-015：使用样例与方法级契约随实现补全。</li>
 * </ul>
 */
package com.acme.common.serialization;
