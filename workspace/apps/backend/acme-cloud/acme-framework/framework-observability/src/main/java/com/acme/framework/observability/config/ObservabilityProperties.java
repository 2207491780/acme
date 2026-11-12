package com.acme.framework.observability.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 可观测配置（FR-019/020、8.1.3 全字段默认值；架构设计 15 章；OTLP 默认关，Q-007）。
 */
@ConfigurationProperties(prefix = "acme.observability")
public class ObservabilityProperties {

    /** 模块开关（3.5 可关闭性）。 */
    private boolean enabled = true;

    /** RED 指标开关。 */
    private boolean red = true;

    /** JVM 指标开关（随 actuator/micrometer 暴露面，14.2.1）。 */
    private boolean jvm = true;

    /** 连接池指标开关（随 actuator/micrometer 暴露面）。 */
    private boolean pool = true;

    /** OTLP 导出组（默认关，Q-007）。 */
    private final Export export = new Export();

    /** 导出配置组。 */
    public static class Export {

        private final Otlp otlp = new Otlp();

        public Otlp getOtlp() {
            return otlp;
        }
    }

    /** OTLP 导出配置（默认 enabled=false，Q-007）。 */
    public static class Otlp {

        private boolean enabled = false;

        /** 导出端点（缺失且启用时显式失败，架构设计 12.3）。 */
        private String endpoint = "";

        /** 导出步长。 */
        private Duration step = Duration.ofSeconds(60);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public Duration getStep() {
            return step;
        }

        public void setStep(Duration step) {
            this.step = step;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isRed() {
        return red;
    }

    public void setRed(boolean red) {
        this.red = red;
    }

    public boolean isJvm() {
        return jvm;
    }

    public void setJvm(boolean jvm) {
        this.jvm = jvm;
    }

    public boolean isPool() {
        return pool;
    }

    public void setPool(boolean pool) {
        this.pool = pool;
    }

    public Export getExport() {
        return export;
    }
}
