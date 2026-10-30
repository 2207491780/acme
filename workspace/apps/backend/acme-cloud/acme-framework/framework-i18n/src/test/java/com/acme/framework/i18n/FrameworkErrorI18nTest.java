package com.acme.framework.i18n;

import com.acme.common.core.CommonError;
import com.acme.common.core.ErrorCode;
import com.acme.framework.cache.support.CacheError;
import com.acme.framework.excel.support.ExcelError;
import com.acme.framework.feign.support.FeignError;
import com.acme.framework.gateway.support.GatewayError;
import com.acme.framework.idempotent.support.IdempotentError;
import com.acme.framework.job.support.JobError;
import com.acme.framework.jpa.support.JpaError;
import com.acme.framework.lock.support.LockError;
import com.acme.framework.ratelimit.support.RateLimitError;
import com.acme.framework.security.support.SecurityError;
import com.acme.framework.storage.support.StorageError;
import com.acme.framework.stream.support.StreamError;
import com.acme.framework.tenant.support.TenantError;
import com.acme.framework.tx.support.TxError;
import com.acme.framework.web.support.WebError;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.PropertyResourceBundle;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 框架错误提示双语齐备性测试（Q-007 裁定 B 落地 framework-i18n）：框架汇总资源须覆盖全部
 * *Error 错误码枚举，zh / en 键集一致、与枚举码一一对应（无缺键、无冗余键）；
 * 另含 common-serialization {@code Json} 编解码码（SYS-00-0007，非枚举承载）。
 */
class FrameworkErrorI18nTest {

    /** common-serialization Json 编解码码（JsonErrorCode.CODEC，非枚举，单列锁定）。 */
    private static final String JSON_CODEC = "SYS-00-0007";

    private static List<ErrorCode> allCodes() {
        return Stream.<ErrorCode[]>of(CommonError.values(), WebError.values(), SecurityError.values(),
                        GatewayError.values(), JpaError.values(), TenantError.values(), CacheError.values(),
                        LockError.values(), StreamError.values(), FeignError.values(), JobError.values(),
                        IdempotentError.values(), ExcelError.values(), StorageError.values(),
                        RateLimitError.values(), TxError.values())
                .flatMap(Arrays::stream)
                .toList();
    }

    private static Set<String> expectedKeys() {
        Set<String> keys = new HashSet<>();
        allCodes().forEach(code -> keys.add(code.code()));
        keys.add(JSON_CODEC);
        return keys;
    }

    @Test
    void everyErrorCodeHasZhAndEnMessages() throws Exception {
        var zh = bundle("i18n/acme-messages_zh.properties");
        var en = bundle("i18n/acme-messages_en.properties");
        for (ErrorCode error : allCodes()) {
            assertThat(zh.containsKey(error.code())).as("zh 缺键：%s", error.code()).isTrue();
            assertThat(en.containsKey(error.code())).as("en 缺键：%s", error.code()).isTrue();
        }
        assertThat(zh.containsKey(JSON_CODEC)).as("zh 缺键：%s", JSON_CODEC).isTrue();
        assertThat(en.containsKey(JSON_CODEC)).as("en 缺键：%s", JSON_CODEC).isTrue();
    }

    @Test
    void noOrphanKeysAndZhEnKeySetsMatch() throws Exception {
        var zh = bundle("i18n/acme-messages_zh.properties");
        var en = bundle("i18n/acme-messages_en.properties");
        assertThat(zh.keySet()).containsExactlyInAnyOrderElementsOf(expectedKeys());
        assertThat(en.keySet()).containsExactlyInAnyOrderElementsOf(expectedKeys());
    }

    private static PropertyResourceBundle bundle(String path) throws Exception {
        try (InputStream in = FrameworkErrorI18nTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("资源文件缺失：%s", path).isNotNull();
            return new PropertyResourceBundle(in);
        }
    }
}
