package com.matrixcode.identity.api;

import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.stereotype.Component;

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
}
