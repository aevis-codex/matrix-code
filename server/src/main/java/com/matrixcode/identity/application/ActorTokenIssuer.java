package com.matrixcode.identity.application;

import java.time.Duration;

/**
 * 登录 token 签发接口。
 *
 * <p>作用域：身份认证应用层边界。主要场景是在用户名密码登录、管理员创建用户后的首次登录等流程中，
 * 由具体实现对接 Sa-Token 或其他会话框架，返回前端可保存的登录凭证。</p>
 */
public interface ActorTokenIssuer {

    /**
     * 为指定用户创建当前系统认可的登录 token。
     *
     * @param userId 用户 ID。
     * @param ttl token 有效期。
     * @return 可返回给桌面端保存的 token 元数据。
     */
    IssuedActorToken issue(String userId, Duration ttl);
}
