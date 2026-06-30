package com.matrixcode.identity.application;

import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class PasswordHashingService {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String FORMAT = "pbkdf2_sha256";
    private static final int ITERATIONS = 310_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 将用户明文密码派生为可持久化哈希。
     *
     * <p>返回值包含算法、迭代次数、盐和派生结果，仓储层只保存该字符串，不保存明文密码。</p>
     */
    public String hash(String password) {
        var normalizedPassword = requirePassword(password);
        var salt = new byte[SALT_BYTES];
        secureRandom.nextBytes(salt);
        var derived = derive(normalizedPassword.toCharArray(), salt, ITERATIONS);
        return FORMAT + "$" + ITERATIONS + "$" + encode(salt) + "$" + encode(derived);
    }

    /**
     * 校验候选密码是否匹配已保存哈希。
     *
     * <p>比较过程使用常量时间比较；无法解析的哈希统一返回 false，避免向调用方暴露内部格式细节。</p>
     */
    public boolean matches(String password, String storedHash) {
        if (password == null || password.isBlank() || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        try {
            var parts = storedHash.split("\\$");
            if (parts.length != 4 || !FORMAT.equals(parts[0])) {
                return false;
            }
            var iterations = Integer.parseInt(parts[1]);
            var salt = decode(parts[2]);
            var expected = decode(parts[3]);
            var actual = derive(password.toCharArray(), salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private byte[] derive(char[] password, byte[] salt, int iterations) {
        try {
            var spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception exception) {
            throw new IllegalStateException("密码哈希生成失败", exception);
        }
    }

    private String requirePassword(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        return password;
    }

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }
}
