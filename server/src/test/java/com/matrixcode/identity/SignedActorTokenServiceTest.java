package com.matrixcode.identity;

import com.matrixcode.identity.application.MatrixCodeAuthProperties;
import com.matrixcode.identity.application.SignedActorTokenService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignedActorTokenServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-27T05:00:00Z"), ZoneOffset.UTC);

    @Test
    void 可以签发并验证身份令牌() {
        var service = new SignedActorTokenService(authProperties(), FIXED_CLOCK);

        var token = service.issue(" user-dev ", Duration.ofMinutes(5));

        assertThat(token).startsWith("v1.");
        assertThat(service.verify(token)).isEqualTo("user-dev");
        assertThat(service.expiresAt(token)).isEqualTo(Instant.parse("2026-06-27T05:05:00Z"));
    }

    @Test
    void 篡改令牌无法通过验证() {
        var service = new SignedActorTokenService(authProperties(), FIXED_CLOCK);
        var token = service.issue("user-dev", Duration.ofMinutes(5));
        var tampered = token.substring(0, token.length() - 2) + "aa";

        assertThatThrownBy(() -> service.verify(tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("身份令牌无效");
    }

    @Test
    void 过期令牌无法通过验证() {
        var properties = authProperties();
        var issuer = new SignedActorTokenService(properties, FIXED_CLOCK);
        var token = issuer.issue("user-dev", Duration.ofSeconds(1));
        var verifier = new SignedActorTokenService(
                properties,
                Clock.fixed(Instant.parse("2026-06-27T05:00:02Z"), ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("身份令牌已过期");
    }

    private MatrixCodeAuthProperties authProperties() {
        var properties = new MatrixCodeAuthProperties();
        properties.setActorTokenSecret("test-signing-secret");
        return properties;
    }
}
