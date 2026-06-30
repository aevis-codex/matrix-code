package com.matrixcode.identity.api;

import java.time.Duration;
import java.util.Optional;

/**
 * Sa-Token 登录态读取适配接口。
 *
 * <p>作用域：Web/API 身份解析边界。主要场景是从当前 HTTP 请求或显式 token 中解析用户 ID，
 * 供权限守卫、SSE 订阅和登录会话管理复用，避免业务控制器直接依赖 Sa-Token 静态 API。</p>
 */
@FunctionalInterface
public interface SaTokenActorSession {

    /**
     * 读取当前请求中的 Sa-Token 登录用户。
     *
     * @return 当前登录用户 ID；未登录或当前上下文不可解析时返回空。
     */
    Optional<String> currentUserId();

    /**
     * 按显式 token 查询登录用户。
     *
     * <p>默认返回空，便于单元测试和非 Web 上下文继续使用轻量实现。生产实现会调用
     * Sa-Token 的 token 反查能力，解决 EventSource 无法设置自定义请求头时的身份解析问题。</p>
     *
     * @param token 待解析的 Sa-Token 明文值。
     * @return token 对应的登录用户 ID；token 为空、无效或已过期时返回空。
     */
    default Optional<String> userIdForToken(String token) {
        return Optional.empty();
    }

    /**
     * 当当前请求 token 剩余有效期低于阈值时自动续期。
     *
     * <p>作用域：强制 Sa-Token API 门禁；场景：用户保持活跃访问时采用滑动会话窗口，
     * 避免登录页暴露固定缓存有效时间输入。</p>
     *
     * @param ttl 续期后的服务端统一会话窗口。
     * @param threshold 触发续期的剩余有效期阈值。
     */
    default void renewIfNeeded(Duration ttl, Duration threshold) {
    }

    /**
     * 创建不绑定 Web 上下文的空实现。
     *
     * <p>作用域：单元测试和非 Web 场景；场景：没有真实 Sa-Token 上下文时安全返回未登录。</p>
     */
    static SaTokenActorSession noop() {
        return Optional::empty;
    }
}
