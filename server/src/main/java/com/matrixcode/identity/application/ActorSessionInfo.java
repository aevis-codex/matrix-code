package com.matrixcode.identity.application;

import java.time.Instant;

/**
 * Sa-Token 登录会话的低敏摘要。
 *
 * <p>该记录用于管理端展示和审计，不允许包含 token 明文；前端只能拿到不可逆指纹。</p>
 */
public record ActorSessionInfo(
        String tokenFingerprint,
        String deviceType,
        String deviceId,
        Instant createdAt,
        long timeoutSeconds
) {
}
