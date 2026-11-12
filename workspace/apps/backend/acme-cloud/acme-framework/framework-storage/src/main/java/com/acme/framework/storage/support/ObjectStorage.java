package com.acme.framework.storage.support;

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
}
