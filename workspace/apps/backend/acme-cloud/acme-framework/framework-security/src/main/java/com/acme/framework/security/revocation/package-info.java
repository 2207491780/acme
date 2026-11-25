/**
 * 撤销检查决策包（REQ-20261125-001736 AC-7）：fail-closed 开关的统一决策组件——
 * 网关 RevocationCheckFilter 与单体边缘 BootstrapAuthFilter 的 Redis 检查异常分支
 * 共用同一开关同语义（配置 acme.security.revocation.fail-closed，默认 false=FAIL_OPEN）。
 */
package com.acme.framework.security.revocation;
