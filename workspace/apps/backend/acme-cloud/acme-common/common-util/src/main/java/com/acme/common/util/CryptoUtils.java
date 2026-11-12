package com.acme.common.util;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * 加解密辅助（FR-003）。仅 JDK 自带能力（BR-004）；密钥材料由调用方提供，common 不管理密钥、不查配置、不落盘。
 * 算法白名单定稿（DP-002 本次定稿）：摘要限 SHA-2 家族枚举；对称限 AES-GCM 认证加密，
 * IV 由调用方提供且按 GCM nonce 一次性约束不得复用，不接受任意算法字符串。
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public final class CryptoUtils {

    /** GCM 认证标签长度（位）。 */
    private static final int GCM_TAG_BITS = 128;

    /** GCM IV 长度（字节，NIST SP 800-38D 推荐值）。 */
    public static final int GCM_IV_BYTES = 12;

    private CryptoUtils() {
    }

    /** 摘要算法白名单（DP-002 本次定稿，SHA-2 家族）。 */
    public enum DigestAlgorithm {
        SHA_256("SHA-256"),
        SHA_384("SHA-384"),
        SHA_512("SHA-512");

        private final String jcaName;

        DigestAlgorithm(String jcaName) {
            this.jcaName = jcaName;
        }

        String jcaName() {
            return jcaName;
        }
    }

    /**
     * 摘要（FR-003）；参数缺失/非法显式失败。
     */
    public static byte[] digest(DigestAlgorithm algorithm, byte[] data) {
        Objects.requireNonNull(algorithm, "algorithm 不能为空（DP-002）");
        Objects.requireNonNull(data, "data 不能为空（DP-002）");
        try {
            return MessageDigest.getInstance(algorithm.jcaName()).digest(data);
        } catch (Exception e) {
            throw new IllegalArgumentException("摘要算法不可用：" + algorithm + "（DP-002）", e);
        }
    }

    /**
     * AES-GCM 对称加密（FR-003）；输出为密文+认证标签。IV 由调用方提供（12 字节），不得跨消息复用。
     */
    public static byte[] encrypt(SecretKeySpec key, byte[] iv, byte[] data) {
        return gcm(Cipher.ENCRYPT_MODE, key, iv, data);
    }

    /**
     * AES-GCM 对称解密（FR-003）；输入为密文+认证标签，篡改或密钥/IV 不符显式失败。
     */
    public static byte[] decrypt(SecretKeySpec key, byte[] iv, byte[] data) {
        return gcm(Cipher.DECRYPT_MODE, key, iv, data);
    }

    private static byte[] gcm(int mode, SecretKeySpec key, byte[] iv, byte[] data) {
        Objects.requireNonNull(key, "key 不能为空（DP-002）");
        Objects.requireNonNull(iv, "iv 不能为空（DP-002）");
        Objects.requireNonNull(data, "data 不能为空（DP-002）");
        if (!"AES".equalsIgnoreCase(key.getAlgorithm())) {
            throw new IllegalArgumentException("仅支持 AES 密钥（DP-002）：" + key.getAlgorithm());
        }
        if (iv.length != GCM_IV_BYTES) {
            throw new IllegalArgumentException("GCM IV 长度必须为 " + GCM_IV_BYTES + " 字节（DP-002）");
        }
        try {
            Cipher instance = Cipher.getInstance("AES/GCM/NoPadding");
            instance.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return instance.doFinal(data);
        } catch (Exception e) {
            throw new IllegalArgumentException("AES-GCM 加解密不可用（DP-002）", e);
        }
    }
}
