package com.acme.framework.idempotent.config;

import com.acme.framework.idempotent.support.InFlightPolicy;
import com.acme.framework.idempotent.support.MissingTokenPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 双入口幂等配置（FR-001~003、8.1.3 全字段默认值；架构设计 15 章）。
 */
@ConfigurationProperties(prefix = "acme.idempotent")
public class IdempotentProperties {

    /** 模块开关（3.5 可关闭性）。 */
    private boolean enabled = true;

    /** 幂等 token 头名。 */
    private String headerName = "X-Acme-Idempotent-Token";

    /** token 缺失处置（默认 REJECT，取舍 #8）。 */
    private MissingTokenPolicy missingTokenPolicy = MissingTokenPolicy.REJECT;

    /** 在途处置（默认 WAIT，Q-D002）。 */
    private InFlightPolicy inFlightPolicy = InFlightPolicy.WAIT;

    /** 在途有界等待（Q-D002 默认 3s）。 */
    private Duration inFlightWait = Duration.ofSeconds(3);

    /** 认领在途 TTL（P 阶段）。 */
    private Duration claimProcessingTtl = Duration.ofSeconds(60);

    /** 完成结果 TTL（FR-003 过期后同键视为新请求）。 */
    private Duration resultTtl = Duration.ofHours(24);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        this.headerName = headerName;
    }

    public MissingTokenPolicy getMissingTokenPolicy() {
        return missingTokenPolicy;
    }

    public void setMissingTokenPolicy(MissingTokenPolicy missingTokenPolicy) {
        this.missingTokenPolicy = missingTokenPolicy;
    }

    public InFlightPolicy getInFlightPolicy() {
        return inFlightPolicy;
    }

    public void setInFlightPolicy(InFlightPolicy inFlightPolicy) {
        this.inFlightPolicy = inFlightPolicy;
    }

    public Duration getInFlightWait() {
        return inFlightWait;
    }

    public void setInFlightWait(Duration inFlightWait) {
        this.inFlightWait = inFlightWait;
    }

    public Duration getClaimProcessingTtl() {
        return claimProcessingTtl;
    }

    public void setClaimProcessingTtl(Duration claimProcessingTtl) {
        this.claimProcessingTtl = claimProcessingTtl;
    }

    public Duration getResultTtl() {
        return resultTtl;
    }

    public void setResultTtl(Duration resultTtl) {
        this.resultTtl = resultTtl;
    }
}
