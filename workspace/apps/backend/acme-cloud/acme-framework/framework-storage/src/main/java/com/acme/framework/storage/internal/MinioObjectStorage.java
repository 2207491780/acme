package com.acme.framework.storage.internal;

import com.acme.common.core.BizException;
import com.acme.framework.storage.config.StorageProperties;
import com.acme.framework.storage.support.ObjectKey;
import com.acme.framework.storage.support.ObjectStorage;
import com.acme.framework.storage.support.StorageError;
import com.acme.framework.storage.support.StorageObject;
import com.google.common.collect.ImmutableMultimap;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.http.Method;
import okhttp3.OkHttpClient;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 适配器（FR-012、Q-004 端到端实证）：io.minio SDK；连接/读取超时显式配置（10.4.2）；
 * SDK 异常经 {@link SdkExceptionTranslator} 统一转换（10.5.3）。
 */
public class MinioObjectStorage implements ObjectStorage {

    private final MinioClient client;
    private final StorageProperties properties;

    public MinioObjectStorage(MinioClient client, StorageProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /** 按配置构造（凭证经环境注入，14.3.3）。 */
    public static MinioObjectStorage connect(StorageProperties properties) {
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(properties.getConnectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(properties.getReadTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .build();
        MinioClient client = MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .httpClient(httpClient)
                .build();
        return new MinioObjectStorage(client, properties);
    }

    @Override
    public void put(StorageObject object) {
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(object.key().path())
                    .stream(object.content(), object.size(), -1)
                    .contentType(object.contentType() == null ? "application/octet-stream" : object.contentType())
                    .build());
        } catch (Exception ex) {
            throw SdkExceptionTranslator.translateMinio(ex);
        }
    }

    @Override
    public InputStream get(ObjectKey key) {
        try {
            return client.getObject(GetObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(key.path())
                    .build());
        } catch (Exception ex) {
            throw SdkExceptionTranslator.translateMinio(ex);
        }
    }

    @Override
    public void remove(ObjectKey key) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(key.path())
                    .build());
        } catch (Exception ex) {
            throw SdkExceptionTranslator.translateMinio(ex);
        }
    }

    @Override
    public boolean exists(ObjectKey key) {
        try {
            client.statObject(StatObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(key.path())
                    .build());
            return true;
        } catch (Exception ex) {
            BizException biz = SdkExceptionTranslator.translateMinio(ex);
            if (biz.errorCode() == StorageError.OBJECT_NOT_FOUND) {
                return false;
            }
            throw biz;
        }
    }

    @Override
    public String url(ObjectKey key) {
        try {
            if (properties.getUrlMode() == StorageProperties.UrlMode.PRESIGN) {
                return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket(properties.getBucket())
                        .object(key.path())
                        .expiry((int) Math.max(properties.getPresignTtl().getSeconds(), 1))
                        .build());
            }
            return properties.getEndpoint() + "/" + properties.getBucket() + "/" + key.path();
        } catch (Exception ex) {
            throw SdkExceptionTranslator.translateMinio(ex);
        }
    }

    /**
     * 直传预签名 PUT URL（REQ-20261117-045754）：SDK 8.5.12 正确姿势——Content-Type 经
     * {@code extraHeaders} 参与签名（presignV4 对 canonical headers 签名，content-type 不在忽略清单），
     * 客户端 PUT 必须携带同值头，否则 SignatureDoesNotMatch；有效期取 {@code presign-ttl}。
     */
    @Override
    public String presignUpload(ObjectKey key, String contentType, long size) {
        try {
            GetPresignedObjectUrlArgs.Builder builder = GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT)
                    .bucket(properties.getBucket())
                    .object(key.path())
                    .expiry((int) Math.max(properties.getPresignTtl().getSeconds(), 1));
            if (contentType != null && !contentType.isBlank()) {
                builder.extraHeaders(ImmutableMultimap.of("Content-Type", contentType));
            }
            return client.getPresignedObjectUrl(builder.build());
        } catch (Exception ex) {
            throw SdkExceptionTranslator.translateMinio(ex);
        }
    }
}
