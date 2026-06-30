package com.matrixcode.identity.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "matrixcode.auth")
public class MatrixCodeAuthProperties {

    private boolean requireSaToken = false;
    private boolean requireSignedActorToken = false;
    private String actorTokenSecret = "";
    private String bootstrapToken = "";
    private long defaultTokenTtlSeconds = 86400;
    private boolean sessionAutoRenewEnabled = true;
    private long sessionRenewThresholdSeconds = 1800;
    private String sessionStore = "memory";
    private String sessionRedisKeyPrefix = "matrixcode:sa-token:";
    private String adminInitialPassword = "";

    public boolean isRequireSaToken() {
        return requireSaToken;
    }

    public void setRequireSaToken(boolean requireSaToken) {
        this.requireSaToken = requireSaToken;
    }

    public boolean isRequireSignedActorToken() {
        return requireSignedActorToken;
    }

    public void setRequireSignedActorToken(boolean requireSignedActorToken) {
        this.requireSignedActorToken = requireSignedActorToken;
    }

    public String getActorTokenSecret() {
        return actorTokenSecret;
    }

    public void setActorTokenSecret(String actorTokenSecret) {
        this.actorTokenSecret = actorTokenSecret == null ? "" : actorTokenSecret.trim();
    }

    public String getBootstrapToken() {
        return bootstrapToken;
    }

    public void setBootstrapToken(String bootstrapToken) {
        this.bootstrapToken = bootstrapToken == null ? "" : bootstrapToken.trim();
    }

    public long getDefaultTokenTtlSeconds() {
        return defaultTokenTtlSeconds;
    }

    public void setDefaultTokenTtlSeconds(long defaultTokenTtlSeconds) {
        this.defaultTokenTtlSeconds = defaultTokenTtlSeconds < 1 ? 86400 : defaultTokenTtlSeconds;
    }

    public Duration defaultTokenTtl() {
        return Duration.ofSeconds(defaultTokenTtlSeconds);
    }

    public boolean isSessionAutoRenewEnabled() {
        return sessionAutoRenewEnabled;
    }

    public void setSessionAutoRenewEnabled(boolean sessionAutoRenewEnabled) {
        this.sessionAutoRenewEnabled = sessionAutoRenewEnabled;
    }

    public long getSessionRenewThresholdSeconds() {
        return sessionRenewThresholdSeconds;
    }

    public void setSessionRenewThresholdSeconds(long sessionRenewThresholdSeconds) {
        this.sessionRenewThresholdSeconds = sessionRenewThresholdSeconds < 1 ? 1800 : sessionRenewThresholdSeconds;
    }

    public Duration sessionRenewThreshold() {
        return Duration.ofSeconds(sessionRenewThresholdSeconds);
    }

    public String getSessionStore() {
        return sessionStore;
    }

    public void setSessionStore(String sessionStore) {
        this.sessionStore = sessionStore == null || sessionStore.isBlank() ? "memory" : sessionStore.trim();
    }

    public String getSessionRedisKeyPrefix() {
        return sessionRedisKeyPrefix;
    }

    public void setSessionRedisKeyPrefix(String sessionRedisKeyPrefix) {
        this.sessionRedisKeyPrefix = sessionRedisKeyPrefix == null || sessionRedisKeyPrefix.isBlank()
                ? "matrixcode:sa-token:"
                : sessionRedisKeyPrefix.trim();
    }

    public String getAdminInitialPassword() {
        return adminInitialPassword;
    }

    public void setAdminInitialPassword(String adminInitialPassword) {
        this.adminInitialPassword = adminInitialPassword == null ? "" : adminInitialPassword;
    }
}
