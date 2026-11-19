package com.acme.platform.system.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * APP 推送配置（REQ-20261119-082531 确认记录⑥）：前缀 {@code acme.notify.push}，全字段默认值。
 * enabled 默认 false（无 projectId/网络时零行为——发送器 bean 不装配，监听器旁路无操作）；
 * url 默认 Expo v2 发送端点（可注入以便测试桩 / 部署侧代理），载荷 {to, title, body, priority=default}。
 */
@ConfigurationProperties("acme.notify.push")
public class PushProperties {

    /** 推送开关（默认 false：关闭时 ExpoPushSender bean 不装配，站内信仅站内触达）。 */
    private boolean enabled = false;

    /** Expo 发送端点（POST；默认官方 exp.host，可注入覆盖）。 */
    private String url = "https://exp.host/--/api/v2/push/send";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
