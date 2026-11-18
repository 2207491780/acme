package com.acme.platform.system.api;

/**
 * 租户公开站点配置内部视图（REQ-20261117-025200 页脚版权，确认记录⑤）：仅暴露登录页页脚两字段，
 * platform-auth 公开端点 /admin/v1/auth/public-config 的后端数据源（@IgnoreTenant 跨租户读）。
 * 无配置行租户返回缺省视图 footerCopyright=null / footerVisible=false（未配置不显示）。
 *
 * @param footerCopyright 页脚版权文案（可空=未配置）
 * @param footerVisible   页脚是否显示（缺省 false）
 */
public record TenantPublicInternalView(String footerCopyright, boolean footerVisible) {
}
