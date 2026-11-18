package com.acme.framework.storage.support;

import java.io.InputStream;

/**
 * 待写入对象（FR-011）。
 */
public record StorageObject(ObjectKey key, InputStream content, long size, String contentType) {

    public StorageObject {
        if (key == null) {
            throw new IllegalArgumentException("key 不能为空");
        }
        if (content == null) {
            throw new IllegalArgumentException("content 不能为空");
        }
    }
}
