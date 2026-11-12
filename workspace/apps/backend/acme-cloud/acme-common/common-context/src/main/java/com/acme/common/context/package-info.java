/**
 * common-context 对外上下文持有者（FR-007–FR-012；Modules.md 2.1、2.4）。
 *
 * <p>共同约束（BR-007）：只存值不做解析、不查库不出网、仅用 ThreadLocal（Modules.md 2.4.1、2.4.2）。</p>
 *
 * <p>暂缓项索引：</p>
 * <ul>
 *   <li>DP-006：各 Context 完整方法集、未绑定读取口径、User/TenantContext tenantId 一致性、跨线程边界情形；</li>
 *   <li>DP-015：使用样例与方法级契约随实现补全。</li>
 * </ul>
 */
package com.acme.common.context;
