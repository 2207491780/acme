package com.acme.common.util;

import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * CryptoUtils 契约测试（FR-003、DP-002 白名单定稿）：加解密往返、白名单拒绝、参数非法显式失败。
 */
class CryptoUtilsTest {

    private static final SecretKeySpec KEY =
            new SecretKeySpec("0123456789abcdef".getBytes(StandardCharsets.UTF_8), "AES");

    @Test
    void digestMatchesJdk() throws Exception {
        byte[] data = "acme".getBytes(StandardCharsets.UTF_8);
        for (CryptoUtils.DigestAlgorithm algorithm : CryptoUtils.DigestAlgorithm.values()) {
            assertArrayEquals(MessageDigest.getInstance(algorithm.name().replace('_', '-')).digest(data),
                    CryptoUtils.digest(algorithm, data));
        }
    }

    @Test
    void encryptDecryptRoundTrip() {
        byte[] iv = new byte[CryptoUtils.GCM_IV_BYTES];
        Arrays.fill(iv, (byte) 7);
        byte[] plain = "机密数据".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = CryptoUtils.encrypt(KEY, iv, plain);
        assertArrayEquals(plain, CryptoUtils.decrypt(KEY, iv, encrypted));
    }

    @Test
    void tamperedCiphertextFailsExplicitly() {
        byte[] iv = new byte[CryptoUtils.GCM_IV_BYTES];
        byte[] encrypted = CryptoUtils.encrypt(KEY, iv, "x".getBytes(StandardCharsets.UTF_8));
        encrypted[0] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> CryptoUtils.decrypt(KEY, iv, encrypted));
    }

    @Test
    void rejectsNonIvLength() {
        assertThrows(IllegalArgumentException.class,
                () -> CryptoUtils.encrypt(KEY, new byte[16], new byte[0]));
    }

    @Test
    void rejectsNonAesKey() {
        SecretKeySpec des = new SecretKeySpec("0123456789abcdef".getBytes(StandardCharsets.UTF_8), "DES");
        assertThrows(IllegalArgumentException.class,
                () -> CryptoUtils.encrypt(des, new byte[CryptoUtils.GCM_IV_BYTES], new byte[0]));
    }

    @Test
    void rejectsNullParams() {
        assertThrows(NullPointerException.class, () -> CryptoUtils.digest(null, new byte[0]));
        assertEquals(12, CryptoUtils.GCM_IV_BYTES);
    }
}
