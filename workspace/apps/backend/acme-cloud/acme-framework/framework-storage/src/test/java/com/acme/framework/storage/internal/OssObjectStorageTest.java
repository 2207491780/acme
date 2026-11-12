package com.acme.framework.storage.internal;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.OSSObject;
import com.acme.common.core.BizException;
import com.acme.framework.storage.config.StorageProperties;
import com.acme.framework.storage.support.ObjectKey;
import com.acme.framework.storage.support.StorageError;
import com.acme.framework.storage.support.StorageObject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OSS 适配器契约级测试（FR-013、AC-013、Q-004：mock SDK 调用面、无云连通实证）。
 */
class OssObjectStorageTest {

    private final List<String> calls = new ArrayList<>();
    private Object nextResult;
    private RuntimeException nextError;

    private OSS stub() {
        return (OSS) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {OSS.class}, (proxy, method, args) -> {
            calls.add(method.getName());
            if (nextError != null) {
                RuntimeException error = nextError;
                nextError = null;
                throw error;
            }
            if ("doesObjectExist".equals(method.getName())) {
                return Boolean.TRUE;
            }
            if ("getObject".equals(method.getName())) {
                OSSObject object = new OSSObject();
                object.setObjectContent(new ByteArrayInputStream("data".getBytes()));
                return object;
            }
            if ("generatePresignedUrl".equals(method.getName())) {
                return new URL("https://oss.example.com/acme/x");
            }
            return nextResult;
        });
    }

    private OssObjectStorage storage() {
        StorageProperties properties = new StorageProperties();
        properties.setBucket("acme");
        return new OssObjectStorage(stub(), properties);
    }

    @Test
    void putDelegatesToSdk() {
        storage().put(new StorageObject(ObjectKey.of("ns", "f.txt"),
                new ByteArrayInputStream("hi".getBytes()), 2, "text/plain"));
        assertThat(calls).containsExactly("putObject");
    }

    @Test
    void getRemoveExistsUrlDelegateToSdk() {
        OssObjectStorage storage = storage();
        assertThat(storage.get(ObjectKey.of("ns", "f.txt"))).isNotNull();
        storage.remove(ObjectKey.of("ns", "f.txt"));
        assertThat(storage.exists(ObjectKey.of("ns", "f.txt"))).isTrue();
        assertThat(storage.url(ObjectKey.of("ns", "f.txt"))).isEqualTo("https://oss.example.com/acme/x");
        assertThat(calls).containsExactly("getObject", "deleteObject", "doesObjectExist", "generatePresignedUrl");
    }

    @Test
    void sdkExceptionIsTranslatedNotLeaked() {
        nextError = new OSSException("msg", "NoSuchKey", "req", "host", "raw", "type", "header");
        assertThatThrownBy(() -> storage().get(ObjectKey.of("ns", "missing")))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(StorageError.OBJECT_NOT_FOUND));
    }
}
