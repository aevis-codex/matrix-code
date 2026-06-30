package com.matrixcode.identity.application;

import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.session.SaTerminalInfo;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

@Component
public class SaTokenActorSessionTerminator implements ActorSessionTerminator {

    /**
     * 调用 Sa-Token 退出当前登录态，清理当前 token 的服务端会话。
     */
    @Override
    public void logout() {
        StpUtil.logout();
    }

    /**
     * 调用 Sa-Token 续期当前 token，并返回脱敏后的会话摘要。
     */
    @Override
    public ActorSessionInfo renewCurrent(Duration ttl) {
        var timeoutSeconds = Math.max(1, ttl == null ? 0 : ttl.toSeconds());
        StpUtil.renewTimeout(timeoutSeconds);
        return sessionInfo(StpUtil.getTokenValue());
    }

    /**
     * 查询指定用户的服务端 token 列表，并只暴露 token 指纹。
     */
    @Override
    public List<ActorSessionInfo> sessions(String userId) {
        var normalizedUserId = requireText(userId, "用户 ID 不能为空");
        return StpUtil.getTerminalListByLoginId(normalizedUserId).stream()
                .map(SaTerminalInfo::getTokenValue)
                .filter(token -> token != null && !token.isBlank())
                .map(this::sessionInfo)
                .toList();
    }

    /**
     * 将指定用户的全部 token 标记为被踢下线。
     */
    @Override
    public void kickout(String userId) {
        StpUtil.kickout(requireText(userId, "用户 ID 不能为空"));
    }

    private ActorSessionInfo sessionInfo(String token) {
        var terminal = terminalInfo(token);
        return new ActorSessionInfo(
                fingerprint(token),
                textOr(terminal == null ? "" : terminal.getDeviceType(), "default"),
                textOr(terminal == null ? "" : terminal.getDeviceId(), ""),
                createdAt(terminal),
                tokenTimeout(token)
        );
    }

    private SaTerminalInfo terminalInfo(String token) {
        try {
            return StpUtil.getTerminalInfoByToken(token);
        } catch (SaTokenException | IllegalStateException exception) {
            return null;
        }
    }

    private long tokenTimeout(String token) {
        try {
            return StpUtil.getTokenTimeout(token);
        } catch (SaTokenException | IllegalStateException exception) {
            return -2;
        }
    }

    private Instant createdAt(SaTerminalInfo terminal) {
        if (terminal == null || terminal.getCreateTime() <= 0) {
            return Instant.EPOCH;
        }
        var createTime = terminal.getCreateTime();
        if (createTime < 100_000_000_000L) {
            return Instant.ofEpochSecond(createTime);
        }
        return Instant.ofEpochMilli(createTime);
    }

    private String fingerprint(String token) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(requireText(token, "token 不能为空").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
