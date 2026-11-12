/**
 * common-contract 对外跨服务协议基元（FR-021–FR-023、FR-033；Modules.md 2.1）。
 * 服务间业务 DTO 不入本模块（BR-016），归各服务 *-api。
 *
 * <p>暂缓项索引：</p>
 * <ul>
 *   <li>DP-008：错误码段位数值表（按「服务 → 模块段区间」登记，Q-010）与分段校验载体不建类、仅在此登记；
 *       跨服务分页协议字段定稿；</li>
 *   <li>DP-009：EventEnvelope 字段集定稿与序列化配合细节；</li>
 *   <li>DP-015：使用样例与方法级契约随实现补全。</li>
 * </ul>
 */
package com.acme.common.contract;
