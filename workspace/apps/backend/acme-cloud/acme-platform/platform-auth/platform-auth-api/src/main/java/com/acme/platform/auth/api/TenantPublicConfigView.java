package com.acme.platform.auth.api;

/**
 * 租户公开站点配置视图（REQ-20261117-025200 确认记录⑤）：公开端点
 * GET /admin/v1/auth/public-config 仅暴露页脚两字段——不含任何敏感配置
 * （数据源经 system 内部 public-view 契约读取，@IgnoreTenant；无行租户返回缺省视图）。
 *
 * @param footerCopyright 页脚版权文案（可空=未配置）
 * @param footerVisible   页脚是否显示（缺省 false）
 */
public record TenantPublicConfigView(String footerCopyright, boolean footerVisible) {

    /** 缺省视图（未配置 / 下游不可用 fail-open，契约 §API：无行/未知租户 → 缺省）。 */
    public static TenantPublicConfigView defaultValue() {
        return new TenantPublicConfigView(null, false);
    }
}
