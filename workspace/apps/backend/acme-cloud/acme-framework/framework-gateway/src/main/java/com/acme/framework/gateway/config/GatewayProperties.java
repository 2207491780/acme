package com.acme.framework.gateway.config;

import com.acme.framework.core.support.RateLimitOnError;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * framework-gateway 配置属性（FR-026、Backend.md 8.1.3）：前缀 {@code acme.gateway}，全字段默认值（架构设计 10）。
 */
@ConfigurationProperties("acme.gateway")
public class GatewayProperties {

    /** 模块开关（FR-026，默认 true）。 */
    private boolean enabled = true;

    private final Auth auth = new Auth();

    private final UserToken userToken = new UserToken();

    private final Revocation revocation = new Revocation();

    private final Ratelimit ratelimit = new Ratelimit();

    private final Gray gray = new Gray();

    private final Xss xss = new Xss();

    private final RequestLog requestLog = new RequestLog();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Auth getAuth() {
        return auth;
    }

    public UserToken getUserToken() {
        return userToken;
    }

    public Revocation getRevocation() {
        return revocation;
    }

    public Ratelimit getRatelimit() {
        return ratelimit;
    }

    public Gray getGray() {
        return gray;
    }

    public Xss getXss() {
        return xss;
    }

    public RequestLog getRequestLog() {
        return requestLog;
    }

    /**
     * 用户令牌验签配置（FR-019；密钥域与内部令牌隔离（acme.security.token.hmac-secret），
     * 两类令牌不得互验（11.6.2）；platform-auth 签发契约对齐本配置）。
     */
    public static class UserToken {

        /** 用户令牌 HMAC 密钥（14.3.3 不入库不硬编码；默认空=启动即失败并提示注入）。 */
        private String hmacSecret = "";

        /** 期望签发方（platform-auth，默认 acme-auth；验签强制匹配）。 */
        private String issuer = "acme-auth";

        /** 时钟偏移（秒，默认 30）。 */
        private long clockSkewSeconds = 30;

        public String getHmacSecret() {
            return hmacSecret;
        }

        public void setHmacSecret(String hmacSecret) {
            this.hmacSecret = hmacSecret;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public long getClockSkewSeconds() {
            return clockSkewSeconds;
        }

        public void setClockSkewSeconds(long clockSkewSeconds) {
            this.clockSkewSeconds = clockSkewSeconds;
        }
    }

    /** 用户令牌撤销检查（Q-D02、FR-004 即时失效）。 */
    public static class Revocation {

        /** 撤销检查开关（Q-D02，默认 true；关闭后行为与未引入一致，Modules.md 3.5）。 */
        private boolean enabled = true;

        /** 判定异常兜底（Q-D02 子项，默认 FAIL_OPEN 可用性优先；FAIL_CLOSE=安全优先拒绝）。 */
        private RateLimitOnError onError = RateLimitOnError.FAIL_OPEN;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public RateLimitOnError getOnError() {
            return onError;
        }

        public void setOnError(RateLimitOnError onError) {
            this.onError = onError;
        }
    }

    /** 按端鉴权策略（FR-019、BR-014、8.2；B/C 独立配置面）。 */
    public static class Auth {

        /** 用户令牌头（Authorization Bearer，platform-auth 契约定稿后按其登记）。 */
        private String tokenHeader = "Authorization";

        private final EndpointPolicy b = new EndpointPolicy();

        private final EndpointPolicy c = new EndpointPolicy();

        public String getTokenHeader() {
            return tokenHeader;
        }

        public void setTokenHeader(String tokenHeader) {
            this.tokenHeader = tokenHeader;
        }

        public EndpointPolicy getB() {
            return b;
        }

        public EndpointPolicy getC() {
            return c;
        }
    }

    /** 单端策略（11.4 语义在验签配置面体现）。 */
    public static class EndpointPolicy {

        /** 本端鉴权开关（默认 true）。 */
        private boolean enabled = true;

        /** 本端公开路径（Ant 风格，默认空；如验收签发端点）。 */
        private List<String> publicPaths = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getPublicPaths() {
            return publicPaths;
        }

        public void setPublicPaths(List<String> publicPaths) {
            this.publicPaths = publicPaths;
        }
    }

    /** 限流（FR-022、Q-D07：Resilience4j 本地内存计数）。 */
    public static class Ratelimit {

        /** 限流开关（FR-022，默认 true）。 */
        private boolean enabled = true;

        /** 判定异常兜底（8.5，默认 FAIL_OPEN）。 */
        private RateLimitOnError onError = RateLimitOnError.FAIL_OPEN;

        /** 计数键上限（默认 10000；LRU 淘汰，防高基数路径内存无限增长）。 */
        private int maxTrackedKeys = 10_000;

        private final Quota b = new Quota(120);

        private final Quota c = new Quota(240);

        private final Fallback fallback = new Fallback();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public RateLimitOnError getOnError() {
            return onError;
        }

        public void setOnError(RateLimitOnError onError) {
            this.onError = onError;
        }

        public int getMaxTrackedKeys() {
            return maxTrackedKeys;
        }

        public void setMaxTrackedKeys(int maxTrackedKeys) {
            this.maxTrackedKeys = maxTrackedKeys;
        }

        public Quota getB() {
            return b;
        }

        public Quota getC() {
            return c;
        }

        public Fallback getFallback() {
            return fallback;
        }

        /** 端别配额（每分钟）。 */
        public static class Quota {

            /** 每分钟配额（B 默认 120：账号+接口；C 默认 240：设备+用户+接口）。 */
            private int quotaPerMinute;

            public Quota(int quotaPerMinute) {
                this.quotaPerMinute = quotaPerMinute;
            }

            public int getQuotaPerMinute() {
                return quotaPerMinute;
            }

            public void setQuotaPerMinute(int quotaPerMinute) {
                this.quotaPerMinute = quotaPerMinute;
            }
        }

        /** 兜底限流（WQ-005）：公开端点/未鉴权流量，key=IP+接口，与 3.1.3 主键口径并行。 */
        public static class Fallback {

            /** 兜底限流开关（WQ-005，默认 true）。 */
            private boolean enabled = true;

            /** 兜底每分钟配额（IP+接口，默认 60）。 */
            private int quotaPerMinute = 60;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public int getQuotaPerMinute() {
                return quotaPerMinute;
            }

            public void setQuotaPerMinute(int quotaPerMinute) {
                this.quotaPerMinute = quotaPerMinute;
            }
        }
    }

    /**
     * 灰度（FR-023，8.6：复用 SCG 原生路由谓词；REQ-20261114-120106 增补用户选择器）：
     * 命中规则（用户白名单 + 按比例确定性哈希）在网关侧判定，命中注入 {@link #header}={@code on} 标记头，
     * 由既有 SCG 路由谓词（Header 断言）消费；规则经 Nacos 配置中心动态刷新（属性重绑定后逐请求生效）。
     */
    public static class Gray {

        /** 灰度用户选择器开关（REQ-20261114-120106，默认 false：不注入标记头，行为与未引入一致）。 */
        private boolean enabled = false;

        /** 灰度标记头（8.6，默认 X-Acme-Gray）。 */
        private String header = "X-Acme-Gray";

        /** 用户白名单（userId 精确匹配，默认空）。 */
        private List<String> users = new ArrayList<>();

        /** 灰度比例（0-100，确定性哈希分桶，同用户恒定命中/恒定不命中；0=全不命中）。 */
        private int percent = 0;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getHeader() {
            return header;
        }

        public void setHeader(String header) {
            this.header = header;
        }

        public List<String> getUsers() {
            return users;
        }

        public void setUsers(List<String> users) {
            this.users = users == null ? new ArrayList<>() : users;
        }

        public int getPercent() {
            return percent;
        }

        public void setPercent(int percent) {
            this.percent = percent;
        }
    }

    /**
     * XSS 清洗（REQ-20261114-120106）：query/form 字段 HTML 实体转义；JSON body 默认不转义
     * （保护公告富文本等业务，REQ-20261114-120102 确认记录②）；仅配置的字段黑名单强制转义。
     */
    public static class Xss {

        /** 清洗开关（默认 true）。 */
        private boolean enabled = true;

        /** 豁免路径（Ant 风格；命中的请求整体原样透传，默认空）。 */
        private List<String> exemptPaths = new ArrayList<>();

        /** JSON body 字段黑名单（顶层字段名；命中字段的字符串值/字符串数组元素强制转义，默认空=JSON 不处理）。 */
        private List<String> escapeFields = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getExemptPaths() {
            return exemptPaths;
        }

        public void setExemptPaths(List<String> exemptPaths) {
            this.exemptPaths = exemptPaths == null ? new ArrayList<>() : exemptPaths;
        }

        public List<String> getEscapeFields() {
            return escapeFields;
        }

        public void setEscapeFields(List<String> escapeFields) {
            this.escapeFields = escapeFields == null ? new ArrayList<>() : escapeFields;
        }
    }

    /** 请求日志（FR-024）。 */
    public static class RequestLog {

        /** 请求日志开关（FR-024，默认 true）。 */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
