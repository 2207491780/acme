package com.acme.framework.storage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;

/**
 * 对象存储配置（FR-011~013、8.1.3 全字段默认值；架构设计 15 章；凭证经环境注入 14.3.3）。
 * 适配器类型缺省 {@code local}（REQ-20261114-071607：本地 dev 无对象存储中间件，
 * 生产经同一接口显式切 minio/oss）。
 */
@ConfigurationProperties(prefix = "acme.storage")
public class StorageProperties {

    /** 模块开关（3.5 可关闭性）。 */
    private boolean enabled = true;

    /** 适配器类型（FR-012/013；local 缺省，REQ-20261114-071607）。 */
    private Type type = Type.LOCAL;

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

    private final Local local = new Local();

    private final Upload upload = new Upload();

    /** 适配器类型。 */
    public enum Type {
        /** 本地盘实现（缺省；REQ-20261114-071607 本地 dev 无对象存储中间件）。 */
        LOCAL,
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

    public Local getLocal() {
        return local;
    }

    public Upload getUpload() {
        return upload;
    }

    /** 本地盘实现配置（type=local；REQ-20261114-071607）。 */
    public static class Local {

        /** 落盘根目录（对象按 ObjectKey 路径落盘；缺失显式失败 SYS-15-0006）。 */
        private String rootDir = "";

        /** 访问 URL 前缀（url = url-base 拼接 ObjectKey 路径；空=仅返回存储相对路径）。 */
        private String urlBase = "";

        public String getRootDir() {
            return rootDir;
        }

        public void setRootDir(String rootDir) {
            this.rootDir = rootDir;
        }

        public String getUrlBase() {
            return urlBase;
        }

        public void setUrlBase(String urlBase) {
            this.urlBase = urlBase;
        }
    }

    /** 上传业务约束（REQ-20261114-071607：文件服务上传上限 / 白名单 / 软删保留期）。 */
    public static class Upload {

        /** 单文件大小上限（缺省 10MB；超限 FILE_SIZE_EXCEEDED，业务侧判定）。 */
        private DataSize maxSize = DataSize.ofMegabytes(10);

        /** 类型白名单（缺省 xlsx/pdf/png/jpg/jpeg/txt/csv；匹配扩展名或 multipart contentType）。 */
        private List<String> allowedContentTypes =
                List.of("xlsx", "pdf", "png", "jpg", "jpeg", "txt", "csv");

        /** 软删保留天数（缺省 7；file-retention-cleanup 清理口径）。 */
        private int retentionDays = 7;

        /** 清理任务 cron（缺省每日 03:00）。 */
        private String retentionCron = "0 0 3 * * *";

        public DataSize getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(DataSize maxSize) {
            this.maxSize = maxSize;
        }

        public List<String> getAllowedContentTypes() {
            return allowedContentTypes;
        }

        public void setAllowedContentTypes(List<String> allowedContentTypes) {
            this.allowedContentTypes = allowedContentTypes == null ? List.of() : List.copyOf(allowedContentTypes);
        }

        public int getRetentionDays() {
            return retentionDays;
        }

        public void setRetentionDays(int retentionDays) {
            this.retentionDays = retentionDays;
        }

        public String getRetentionCron() {
            return retentionCron;
        }

        public void setRetentionCron(String retentionCron) {
            this.retentionCron = retentionCron;
        }
    }
}
