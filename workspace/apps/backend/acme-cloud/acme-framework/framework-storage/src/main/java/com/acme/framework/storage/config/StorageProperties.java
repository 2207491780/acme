package com.acme.framework.storage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 对象存储配置（FR-011~013、8.1.3 全字段默认值；架构设计 15 章；凭证经环境注入 14.3.3）。
 */
@ConfigurationProperties(prefix = "acme.storage")
public class StorageProperties {

    /** 模块开关（3.5 可关闭性）。 */
    private boolean enabled = true;

    /** 适配器类型（FR-012/013）。 */
    private Type type = Type.MINIO;

    /** 服务端点（缺失显式失败 SYS-15-0003）。 */
    private String endpoint = "";

    /** 访问键（env 注入，14.3.3 不入库不硬编码）。 */
    private String accessKey = "";

    /** 密钥（env 注入，14.3.3）。 */
    private String secretKey = "";

    /** 桶。 */
    private String bucket = "acme";

    /** 预签名有效期。 */
    private Duration presignTtl = Duration.ofMinutes(15);

    /** 连接超时（10.4.2 显式配置）。 */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /** 读取超时（10.4.2 显式配置）。 */
    private Duration readTimeout = Duration.ofSeconds(30);

    /** 访问 URL 模式（架构设计 9.2：预签名或公共 URL）。 */
    private UrlMode urlMode = UrlMode.PRESIGN;

    /** 适配器类型。 */
    public enum Type {
        /** MinIO（S3 兼容，Q-004 端到端实证）。 */
        MINIO,
        /** 阿里云 OSS（Q-004 契约级验证）。 */
        OSS
    }

    /** 访问 URL 模式。 */
    public enum UrlMode {
        /** 预签名 URL（默认）。 */
        PRESIGN,
        /** 公共 URL。 */
        PUBLIC
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public Duration getPresignTtl() {
        return presignTtl;
    }

    public void setPresignTtl(Duration presignTtl) {
        this.presignTtl = presignTtl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public UrlMode getUrlMode() {
        return urlMode;
    }

    public void setUrlMode(UrlMode urlMode) {
        this.urlMode = urlMode;
    }
}
