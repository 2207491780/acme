package com.acme.framework.storage.internal;

import com.acme.common.core.BizException;
import com.acme.framework.storage.support.StorageError;

/**
 * SDK 异常统一转换（FR-011、10.5.3/10.5.4：不外泄 SDK 细节，只映射错误码语义）。
 * MinIO / OSS 异常形态分别适配；未知异常统一按网络失败口径（可判定）。
 */
public final class SdkExceptionTranslator {

    private SdkExceptionTranslator() {
    }

    /** MinIO 侧转换（io.minio）。 */
    public static BizException translateMinio(Exception ex) {
        String code = minioErrorCode(ex);
        if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code)) {
            return new BizException(StorageError.OBJECT_NOT_FOUND);
        }
        if ("NoSuchBucket".equals(code)) {
            return new BizException(StorageError.BUCKET_UNAVAILABLE);
        }
        if ("InvalidAccessKeyId".equals(code) || "SignatureDoesNotMatch".equals(code) || "AccessDenied".equals(code)) {
            return new BizException(StorageError.CREDENTIAL_INVALID);
        }
        return new BizException(StorageError.NETWORK_FAILURE, null, ex);
    }

    /** OSS 侧转换（com.aliyun.oss）。 */
    public static BizException translateOss(Exception ex) {
        if (ex instanceof com.aliyun.oss.OSSException ossEx) {
            String code = ossEx.getErrorCode();
            if ("NoSuchKey".equals(code)) {
                return new BizException(StorageError.OBJECT_NOT_FOUND);
            }
            if ("NoSuchBucket".equals(code)) {
                return new BizException(StorageError.BUCKET_UNAVAILABLE);
            }
            if ("InvalidAccessKeyId".equals(code) || "SignatureDoesNotMatch".equals(code)
                    || "AccessDenied".equals(code)) {
                return new BizException(StorageError.CREDENTIAL_INVALID);
            }
            return new BizException(StorageError.NETWORK_FAILURE, null, ossEx);
        }
        if (ex instanceof com.aliyun.oss.ClientException) {
            return new BizException(StorageError.NETWORK_FAILURE, null, ex);
        }
        return new BizException(StorageError.NETWORK_FAILURE, null, ex);
    }

    private static String minioErrorCode(Exception ex) {
        if (ex instanceof io.minio.errors.ErrorResponseException responseEx) {
            return responseEx.errorResponse() == null ? null : responseEx.errorResponse().code();
        }
        return null;
    }
}
