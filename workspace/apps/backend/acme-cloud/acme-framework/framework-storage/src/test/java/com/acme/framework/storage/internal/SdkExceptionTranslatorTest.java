package com.acme.framework.storage.internal;

import com.acme.common.core.BizException;
import com.acme.framework.storage.support.StorageError;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSSException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SDK 异常映射测试（FR-011、AC-011/013、10.5.3：底层 SDK 异常不外泄）。
 */
class SdkExceptionTranslatorTest {

    @Test
    void ossNoSuchKeyMapsToObjectNotFound() {
        BizException ex = SdkExceptionTranslator.translateOss(new OSSException("msg", "NoSuchKey", "req", "host", "raw", "type", "header"));
        assertThat(ex.errorCode()).isEqualTo(StorageError.OBJECT_NOT_FOUND);
    }

    @Test
    void ossCredentialErrorsMapToCredentialInvalid() {
        assertThat(SdkExceptionTranslator.translateOss(
                        new OSSException("msg", "InvalidAccessKeyId", "req", "host", "raw", "type", "header")).errorCode())
                .isEqualTo(StorageError.CREDENTIAL_INVALID);
        assertThat(SdkExceptionTranslator.translateOss(
                        new OSSException("msg", "SignatureDoesNotMatch", "req", "host", "raw", "type", "header")).errorCode())
                .isEqualTo(StorageError.CREDENTIAL_INVALID);
    }

    @Test
    void ossNoSuchBucketMapsToBucketUnavailable() {
        assertThat(SdkExceptionTranslator.translateOss(
                        new OSSException("msg", "NoSuchBucket", "req", "host", "raw", "type", "header")).errorCode())
                .isEqualTo(StorageError.BUCKET_UNAVAILABLE);
    }

    @Test
    void ossClientExceptionMapsToNetworkFailure() {
        assertThat(SdkExceptionTranslator.translateOss(new ClientException("connect refused")).errorCode())
                .isEqualTo(StorageError.NETWORK_FAILURE);
    }

    @Test
    void unknownMinioExceptionMapsToNetworkFailure() {
        assertThat(SdkExceptionTranslator.translateMinio(new RuntimeException("boom")).errorCode())
                .isEqualTo(StorageError.NETWORK_FAILURE);
    }
}
