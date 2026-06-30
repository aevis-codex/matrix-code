package com.matrixcode.identity.api;

import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class StpUtilSaTokenActorSession implements SaTokenActorSession {

    /**
     * 从 Sa-Token Web 上下文读取当前登录用户。
     *
     * <p>未登录、token 无效或当前线程没有请求上下文时均按未登录处理，由上层解析器决定
     * 是否回退过渡身份或返回 401。</p>
     */
    @Override
    public Optional<String> currentUserId() {
        try {
            if (!StpUtil.isLogin()) {
                return Optional.empty();
            }
            var loginId = StpUtil.getLoginIdAsString();
            if (loginId == null || loginId.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(loginId.trim());
        } catch (SaTokenException | IllegalStateException exception) {
            return Optional.empty();
        }
    }

    /**
     * 通过 Sa-Token 明文值反查登录用户。
     *
     * <p>该路径用于浏览器原生 EventSource 等不能设置 Authorization Header 的场景。
     * 业务层只接收 token 对应的用户 ID，不把 token 放入日志或异常消息，避免泄露登录凭据。</p>
     */
    @Override
    public Optional<String> userIdForToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            var loginId = StpUtil.getLoginIdByToken(token.trim());
            if (loginId == null) {
                return Optional.empty();
            }
            var normalizedLoginId = loginId.toString();
            if (normalizedLoginId.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(normalizedLoginId.trim());
        } catch (SaTokenException | IllegalStateException exception) {
            return Optional.empty();
        }
    }

    /**
     * 在当前请求已携带有效 Sa-Token 时按滑动窗口自动续期。
     *
     * <p>只处理当前请求上下文中的 token；无登录态、永久 token 或读取失败时保持无副作用，
     * 由显式续期接口和登录接口继续承担可观测审计。</p>
     */
    @Override
    public void renewIfNeeded(Duration ttl, Duration threshold) {
        try {
            if (!StpUtil.isLogin()) {
                return;
            }
            var remainingSeconds = StpUtil.getTokenTimeout();
            if (remainingSeconds < 0) {
                return;
            }
            var thresholdSeconds = Math.max(1, threshold == null ? 0 : threshold.toSeconds());
            if (remainingSeconds <= thresholdSeconds) {
                StpUtil.renewTimeout(Math.max(1, ttl == null ? 0 : ttl.toSeconds()));
            }
        } catch (SaTokenException | IllegalStateException exception) {
            // 自动续期不能影响主请求鉴权结果。
        }
    }
}
