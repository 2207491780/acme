package com.acme.platform.system.api.dto;

/**
 * 租户级系统配置视图（REQ-20261116-230354；version 经全局 Long→String 序列化为字符串）。
 * 无配置行租户返回缺省视图 { watermarkEnabled:true, captchaEnabled:false, lockIdleTimeoutMs:0,
 * footerCopyright:null, footerVisible:false, version:"0" }
 * （captchaEnabled 缺省口径 REQ-20261117-011514：内置默认关闭；
 * 页脚缺省不显示，REQ-20261117-025200 确认记录⑤）。
 *
 * @param watermarkEnabled   水印开关
 * @param captchaEnabled     登录图形验证码开关
 * @param lockIdleTimeoutMs  闲置锁屏时长（毫秒）
 * @param footerCopyright    页脚版权文案（可空=未配置；REQ-20261117-025200）
 * @param footerVisible      页脚是否显示（缺省 false；REQ-20261117-025200）
 * @param version            乐观锁版本（wire 字符串化）
 */
public record AdminSystemConfigView(boolean watermarkEnabled, boolean captchaEnabled,
                                    int lockIdleTimeoutMs, String footerCopyright, boolean footerVisible,
                                    Long version) {
}
