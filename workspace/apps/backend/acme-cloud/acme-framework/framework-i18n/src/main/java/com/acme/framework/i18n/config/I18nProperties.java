package com.acme.framework.i18n.config;

import com.acme.common.contract.ServiceHeaders;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 多语言配置（FR-006/007、8.1.3 全字段默认值；架构设计 15 章；默认 zh-CN 回退 en，Q-009）。
 */
@ConfigurationProperties(prefix = "acme.i18n")
public class I18nProperties {

    /** 模块开关（3.5 可关闭性）。 */
    private boolean enabled = true;

    /** 默认 Locale（头缺失/非法回退，Q-009）。 */
    private String defaultLocale = "zh-CN";

    /** 回退 Locale（Q-009）。 */
    private String fallbackLocale = "en";

    /** 协商头名。 */
    private String headerName = "Accept-Language";

    /** 覆盖头名（优先于协商头；默认同透传约定头 {@link ServiceHeaders#LOCALE}，11.6.3）。 */
    private String overrideHeader = ServiceHeaders.LOCALE;

    /** 消息资源 basename（DP-012；框架汇总资源随模块附带）。 */
    private List<String> basenames = new ArrayList<>(List.of("i18n/acme-messages"));

    /** 缺 key 口径（DP-012：默认返回 key 本身）。 */
    private MissingKeyPolicy missingKey = MissingKeyPolicy.RETURN_KEY;

    /** 缺 key 口径。 */
    public enum MissingKeyPolicy {
        /** 返回 key 本身（默认，不抛、不阻断业务）。 */
        RETURN_KEY,
        /** 抛出异常（严格模式）。 */
        THROW
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDefaultLocale() {
        return defaultLocale;
    }

    public void setDefaultLocale(String defaultLocale) {
        this.defaultLocale = defaultLocale;
    }

    public String getFallbackLocale() {
        return fallbackLocale;
    }

    public void setFallbackLocale(String fallbackLocale) {
        this.fallbackLocale = fallbackLocale;
    }

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        this.headerName = headerName;
    }

    public String getOverrideHeader() {
        return overrideHeader;
    }

    public void setOverrideHeader(String overrideHeader) {
        this.overrideHeader = overrideHeader;
    }

    public List<String> getBasenames() {
        return basenames;
    }

    public void setBasenames(List<String> basenames) {
        this.basenames = basenames;
    }

    public MissingKeyPolicy getMissingKey() {
        return missingKey;
    }

    public void setMissingKey(MissingKeyPolicy missingKey) {
        this.missingKey = missingKey;
    }
}
