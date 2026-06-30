package com.matrixcode.identity.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

@Service
public class SignedActorTokenService {

    private static final String VERSION = "v1";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder TOKEN_DECODER = Base64.getUrlDecoder();

    private final MatrixCodeAuthProperties properties;
    private final Clock clock;

    @Autowired
    public SignedActorTokenService(MatrixCodeAuthProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public SignedActorTokenService(MatrixCodeAuthProperties properties, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "authProperties 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /**
     * 为用户签发无状态 HMAC 身份令牌。
     *
     * <p>令牌只包含用户 ID 和过期时间，不保存 prompt、模型响应、向量正文、工具输出、
     * 命令输出或任何密钥。签名密钥必须由部署环境提供，不能写入仓库。</p>
     *
     * @param userId 用户 ID。
     * @param ttl 令牌有效期；为空或非正数时使用默认有效期。
     * @return 可放入 `Authorization: Bearer ...` 的签名令牌。
     */
    public String issue(String userId, Duration ttl) {
        var normalizedUserId = requireText(userId, "用户编号不能为空");
        var normalizedTtl = ttl == null || ttl.isZero() || ttl.isNegative() ? properties.defaultTokenTtl() : ttl;
        var expiresAt = clock.instant().plus(normalizedTtl);
        var encodedUserId = encode(normalizedUserId);
        var payload = VERSION + "." + encodedUserId + "." + expiresAt.getEpochSecond();
        return payload + "." + signature(payload);
    }

    /**
     * 验证身份令牌签名和过期时间，并返回令牌声明的用户 ID。
     *
     * @param token 待验证的身份令牌。
     * @return 令牌声明的用户 ID。
     */
    public String verify(String token) {
        var parsed = parse(token);
        if (!signatureMatches(signature(parsed.payload()), parsed.signature())) {
            throw new IllegalArgumentException("身份令牌无效");
        }
        if (!parsed.expiresAt().isAfter(clock.instant())) {
            throw new IllegalArgumentException("身份令牌已过期");
        }
        return parsed.userId();
    }

    /**
     * 读取令牌中的过期时间，用于签发接口返回可展示的有效期。
     *
     * @param token 已签发令牌。
     * @return 令牌过期时间。
     */
    public Instant expiresAt(String token) {
        return parse(token).expiresAt();
    }

    private ParsedToken parse(String token) {
        var normalizedToken = requireText(token, "身份令牌不能为空");
        var parts = normalizedToken.split("\\.");
        if (parts.length != 4 || !VERSION.equals(parts[0])) {
            throw new IllegalArgumentException("身份令牌格式不正确");
        }
        try {
            var userId = new String(TOKEN_DECODER.decode(parts[1]), StandardCharsets.UTF_8);
            var expiresAt = Instant.ofEpochSecond(Long.parseLong(parts[2]));
            var payload = parts[0] + "." + parts[1] + "." + parts[2];
            return new ParsedToken(requireText(userId, "身份令牌用户不能为空"), expiresAt, payload, parts[3]);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("身份令牌格式不正确", exception);
        }
    }

    private String signature(String payload) {
        try {
            var mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return TOKEN_ENCODER.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("身份令牌签名失败", exception);
        }
    }

    private boolean signatureMatches(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String signingSecret() {
        return requireText(properties.getActorTokenSecret(), "身份令牌签名密钥未配置");
    }

    private String encode(String value) {
        return TOKEN_ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private record ParsedToken(String userId, Instant expiresAt, String payload, String signature) {
    }
}
