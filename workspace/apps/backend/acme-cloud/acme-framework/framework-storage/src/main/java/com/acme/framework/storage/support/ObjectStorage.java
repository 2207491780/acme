package com.acme.framework.storage.support;

import com.acme.common.core.BizException;

import java.io.InputStream;

/**
 * 对象存储统一门面（FR-011/AC-011）：五操作语义统一，底层 SDK 异常不外泄（10.5.3，统一转 BizException）；
 * 访问 URL 按配置为预签名（{@code presign-ttl}）或公共 URL（架构设计 9.2）。
 */
public interface ObjectStorage {

    /** 写入对象。 */
    void put(StorageObject object);

    /** 读取对象内容流。 */
    InputStream get(ObjectKey key);

    /** 删除对象（不存在视为幂等成功）。 */
    void remove(ObjectKey key);

    /** 对象存在性。 */
    boolean exists(ObjectKey key);

    /** 访问 URL（预签名或公共 URL，按配置）。 */
    String url(ObjectKey key);

    /**
     * 直传预签名 PUT URL（REQ-20261117-045754 确认①）：返回客户端直接 PUT 原始字节的目标 URL；
     * {@code contentType} 参与签名，客户端 PUT 必须携带同值 {@code Content-Type} 头（响应头集合由
     * 业务层组装）。{@code size} 仅作语义携带（S3 预签名不约束长度）。缺省不支持（LOCAL 无预签名
     * 语义，PRESIGN_UNSUPPORTED），MinIO/OSS 适配器覆写。
     */
    default String presignUpload(ObjectKey key, String contentType, long size) {
        throw new BizException(StorageError.PRESIGN_UNSUPPORTED);
    }
}
