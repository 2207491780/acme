package com.acme.framework.core.config;

/**
 * 模块自动装配基类（FR-005；Modules.md 3.3.1 下沉面）。
 *
 * <p>约定 1：子模块 {@code @AutoConfiguration} 类标注 {@link ConditionalOnAcmeModule @ConditionalOnAcmeModule("&lt;name&gt;")}
 * （Backend.md 8.1.1 可关闭性）。</p>
 * <p>约定 2：配置属性以 {@code *Properties} 承载、前缀 {@code acme.<module>}、全字段默认值（Backend.md 8.1.3、BR-006）。</p>
 * <p>约定 3：可共享装配辅助随 P2+ 实证需要再增，不做空壳预留（架构设计 4.5）。</p>
 */
public abstract class AcmeModuleAutoConfiguration {
}
