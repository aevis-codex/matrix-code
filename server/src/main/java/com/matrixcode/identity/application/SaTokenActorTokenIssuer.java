package com.matrixcode.identity.application;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

@Component
public class SaTokenActorTokenIssuer implements ActorTokenIssuer {

    private final Clock clock;

    public SaTokenActorTokenIssuer() {
        this(Clock.systemUTC());
    }

    SaTokenActorTokenIssuer(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /**
     * 通过 Sa-Token 创建登录态，并返回本次登录生成的 token。
     *
     * <p>该方法只在受 bootstrap token 保护的签发接口中调用。调用后 Sa-Token 会在当前
     * Web 上下文写入登录态，后续请求通过 Authorization Bearer token 解析。</p>
     */
    @Override
    public IssuedActorToken issue(String userId, Duration ttl) {
        var normalizedUserId = requireText(userId, "用户编号不能为空");
        var timeoutSeconds = Math.max(1, ttl.toSeconds());
        StpUtil.login(normalizedUserId, timeoutSeconds);
        return new IssuedActorToken(
                normalizedUserId,
                StpUtil.getTokenValue(),
                clock.instant().plusSeconds(timeoutSeconds)
        );
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
