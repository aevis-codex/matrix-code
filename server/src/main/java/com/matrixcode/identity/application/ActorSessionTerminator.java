package com.matrixcode.identity.application;

import java.time.Duration;
import java.util.List;

/**
 * 登录会话管理接口。
 *
 * <p>作用域：身份认证应用层边界。主要场景是退出登录、续期当前 Sa-Token 会话、查询用户在线会话，
 * 以及超级管理员强制踢下线指定用户。实现类必须避免向前端返回 token 明文。</p>
 */
public interface ActorSessionTerminator {

    /**
     * 退出当前请求绑定的 Sa-Token 登录态。
     */
    void logout();

    /**
     * 续期当前请求绑定的 Sa-Token 登录态。
     *
     * @param ttl 新的 token 有效期。
     * @return 续期后的当前会话概要，不能包含 token 明文。
     */
    default ActorSessionInfo renewCurrent(Duration ttl) {
        throw new UnsupportedOperationException("当前会话实现不支持续期");
    }

    /**
     * 查询指定用户的登录会话概要。
     *
     * @param userId 用户 ID。
     * @return 用户当前可见会话列表；返回值不能包含 token 明文。
     */
    default List<ActorSessionInfo> sessions(String userId) {
        return List.of();
    }

    /**
     * 踢下线指定用户的所有登录会话。
     *
     * @param userId 用户 ID。
     */
    default void kickout(String userId) {
        throw new UnsupportedOperationException("当前会话实现不支持踢下线");
    }
}
