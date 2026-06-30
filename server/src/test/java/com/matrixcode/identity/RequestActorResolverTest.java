package com.matrixcode.identity;

import com.matrixcode.identity.api.RequestActorResolver;
import com.matrixcode.identity.api.SaTokenActorSession;
import com.matrixcode.identity.application.MatrixCodeAuthProperties;
import com.matrixcode.identity.application.SignedActorTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestActorResolverTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-27T05:00:00Z"), ZoneOffset.UTC);

    @Test
    void 优先使用SaToken登录态解析当前用户() {
        var properties = authProperties(false);
        var resolver = new RequestActorResolver(
                new SignedActorTokenService(properties, FIXED_CLOCK),
                properties,
                () -> Optional.of("user-sa")
        );
        var request = new MockHttpServletRequest();
        request.addHeader(RequestActorResolver.CURRENT_USER_HEADER, "user-sa");

        assertThat(resolver.resolve(request)).isEqualTo("user-sa");
    }

    @Test
    void SaToken用户和用户头不一致时拒绝请求() {
        var properties = authProperties(false);
        var resolver = new RequestActorResolver(
                new SignedActorTokenService(properties, FIXED_CLOCK),
                properties,
                () -> Optional.of("user-sa")
        );
        var request = new MockHttpServletRequest();
        request.addHeader(RequestActorResolver.CURRENT_USER_HEADER, "user-other");

        assertThatThrownBy(() -> resolver.resolve(request))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void 强制SaToken模式拒绝过渡身份() {
        var properties = authProperties(false);
        properties.setRequireSaToken(true);
        var resolver = new RequestActorResolver(
                new SignedActorTokenService(properties, FIXED_CLOCK),
                properties,
                SaTokenActorSession.noop()
        );
        var request = new MockHttpServletRequest();
        request.addHeader(RequestActorResolver.CURRENT_USER_HEADER, "user-dev");

        assertThatThrownBy(() -> resolver.resolve(request))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void 强制SaToken模式可以从Bearer令牌解析当前用户() {
        var properties = authProperties(false);
        properties.setRequireSaToken(true);
        var resolver = new RequestActorResolver(
                new SignedActorTokenService(properties, FIXED_CLOCK),
                properties,
                new SaTokenActorSession() {
                    @Override
                    public Optional<String> currentUserId() {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<String> userIdForToken(String token) {
                        return "sa-token-user-dev".equals(token) ? Optional.of("user-dev") : Optional.empty();
                    }
                }
        );
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer sa-token-user-dev");
        request.addHeader(RequestActorResolver.CURRENT_USER_HEADER, "user-dev");

        assertThat(resolver.resolve(request)).isEqualTo("user-dev");
    }

    @Test
    void 优先使用签名令牌解析当前用户() {
        var properties = authProperties(true);
        var tokenService = new SignedActorTokenService(properties, FIXED_CLOCK);
        var resolver = new RequestActorResolver(tokenService, properties);
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + tokenService.issue("user-dev", Duration.ofMinutes(5)));

        assertThat(resolver.resolve(request)).isEqualTo("user-dev");
    }

    @Test
    void 强制签名模式拒绝裸用户头() {
        var properties = authProperties(true);
        var resolver = new RequestActorResolver(new SignedActorTokenService(properties, FIXED_CLOCK), properties);
        var request = new MockHttpServletRequest();
        request.addHeader(RequestActorResolver.CURRENT_USER_HEADER, "user-dev");

        assertThatThrownBy(() -> resolver.resolve(request))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void 令牌用户和用户头不一致时拒绝请求() {
        var properties = authProperties(false);
        var tokenService = new SignedActorTokenService(properties, FIXED_CLOCK);
        var resolver = new RequestActorResolver(tokenService, properties);
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + tokenService.issue("user-dev", Duration.ofMinutes(5)));
        request.addHeader(RequestActorResolver.CURRENT_USER_HEADER, "user-test");

        assertThatThrownBy(() -> resolver.resolve(request))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void 非强制模式保留用户头兼容行为() {
        var properties = authProperties(false);
        var resolver = new RequestActorResolver(new SignedActorTokenService(properties, FIXED_CLOCK), properties);
        var request = new MockHttpServletRequest();
        request.addHeader(RequestActorResolver.CURRENT_USER_HEADER, " user-dev ");

        assertThat(resolver.resolve(request)).isEqualTo("user-dev");
    }

    private MatrixCodeAuthProperties authProperties(boolean requireSignedActorToken) {
        var properties = new MatrixCodeAuthProperties();
        properties.setActorTokenSecret("test-signing-secret");
        properties.setRequireSignedActorToken(requireSignedActorToken);
        return properties;
    }
}
