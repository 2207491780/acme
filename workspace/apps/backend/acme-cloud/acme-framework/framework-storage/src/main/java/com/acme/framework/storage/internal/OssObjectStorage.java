package com.acme.framework.storage.internal;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.acme.framework.storage.config.StorageProperties;
import com.acme.framework.storage.support.ObjectKey;
import com.acme.framework.storage.support.ObjectStorage;
import com.acme.framework.storage.support.StorageObject;

import java.io.InputStream;
import java.net.URL;
import java.util.Date;

/**
 * 阿里云 OSS 适配器（FR-013、Q-004 契约级验证：mock SDK 调用面，无云连通实证）；
 * 连接/读取超时显式配置（10.4.2）；SDK 异常经 {@link SdkExceptionTranslator} 统一转换（10.5.3）。
 */
public class OssObjectStorage implements ObjectStorage {

    private final OSS client;
    private final StorageProperties properties;

    public OssObjectStorage(OSS client, StorageProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /** 按配置构造（凭证经环境注入，14.3.3）。 */
    public static OssObjectStorage connect(StorageProperties properties) {
        ClientBuilderConfiguration configuration = new ClientBuilderConfiguration();
        configuration.setConnectionTimeout((int) properties.getConnectTimeout().toMillis());
        configuration.setSocketTimeout((int) properties.getReadTimeout().toMillis());
        OSS client = new OSSClientBuilder().build(
                properties.getEndpoint(), properties.getAccessKey(), properties.getSecretKey(), configuration);
        return new OssObjectStorage(client, properties);
    }

    @Override
    public void put(StorageObject object) {
        try {
            client.putObject(properties.getBucket(), object.key().path(), object.content());
        } catch (Exception ex) {
            throw SdkExceptionTranslator.translateOss(ex);
        }
    }

    @Override
    public InputStream get(ObjectKey key) {
        try {
            return client.getObject(properties.getBucket(), key.path()).getObjectContent();
        } catch (Exception ex) {
            throw SdkExceptionTranslator.translateOss(ex);
        }
    }

    @Override
    public void remove(ObjectKey key) {
        try {
            client.deleteObject(properties.getBucket(), key.path());
        } catch (Exception ex) {
            throw SdkExceptionTranslator.translateOss(ex);
        }
    }

    @Override
    public boolean exists(ObjectKey key) {
        try {
            return client.doesObjectExist(properties.getBucket(), key.path());
        } catch (Exception ex) {
            throw SdkExceptionTranslator.translateOss(ex);
        }
    }

    @Override
    public String url(ObjectKey key) {
        try {
            if (properties.getUrlMode() == StorageProperties.UrlMode.PRESIGN) {
                Date expiration = new Date(System.currentTimeMillis()
                        + Math.max(properties.getPresignTtl().toMillis(), 1000L));
                URL url = client.generatePresignedUrl(properties.getBucket(), key.path(), expiration);
                return url.toString();
            }
            return properties.getEndpoint() + "/" + properties.getBucket() + "/" + key.path();
        } catch (Exception ex) {
            throw SdkExceptionTranslator.translateOss(ex);
        }
    }
}
