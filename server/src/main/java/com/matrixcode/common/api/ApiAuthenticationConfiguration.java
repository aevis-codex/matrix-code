package com.matrixcode.common.api;

import com.matrixcode.identity.api.RequestActorResolver;
import com.matrixcode.identity.api.SaTokenActorSession;
import com.matrixcode.identity.application.MatrixCodeAuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Objects;

@Configuration
public class ApiAuthenticationConfiguration implements WebMvcConfigurer {

    private static final String[] PUBLIC_API_PATHS = {
            "/api/projects/*/identity/auth/login",
            "/api/projects/*/identity/auth/actor-token",
            "/api/projects/*/events/stream"
    };

    private final MatrixCodeAuthProperties authProperties;
    private final RequestActorResolver actorResolver;
    private final SaTokenActorSession saTokenActorSession;

    public ApiAuthenticationConfiguration(
            MatrixCodeAuthProperties authProperties,
            RequestActorResolver actorResolver,
            SaTokenActorSession saTokenActorSession
    ) {
        this.authProperties = Objects.requireNonNull(authProperties, "authProperties 不能为空");
        this.actorResolver = Objects.requireNonNull(actorResolver, "actorResolver 不能为空");
        this.saTokenActorSession = Objects.requireNonNull(saTokenActorSession, "saTokenActorSession 不能为空");
    }

    /**
     * 为所有业务 API 增加默认登录态门禁。
     *
     * <p>各控制器仍保留项目成员、角色和操作者一致性校验；本拦截器只负责在生产强制
     * Sa-Token 模式下兜底拦截漏接权限守卫的新 API。静态资源和 Actuator 不匹配
     * {@code /api/**}，天然保持公开；登录、bootstrap 签发和事件流入口由自身逻辑处理。</p>
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RequireSaTokenApiInterceptor(authProperties, actorResolver, saTokenActorSession))
                .addPathPatterns("/api/**")
                .excludePathPatterns(PUBLIC_API_PATHS);
    }

    private static final class RequireSaTokenApiInterceptor implements HandlerInterceptor {

        private final MatrixCodeAuthProperties authProperties;
        private final RequestActorResolver actorResolver;
        private final SaTokenActorSession saTokenActorSession;

        private RequireSaTokenApiInterceptor(
                MatrixCodeAuthProperties authProperties,
                RequestActorResolver actorResolver,
                SaTokenActorSession saTokenActorSession
        ) {
            this.authProperties = authProperties;
            this.actorResolver = actorResolver;
            this.saTokenActorSession = saTokenActorSession;
        }

        /**
         * 在请求进入控制器前校验 Sa-Token 登录态。
         *
         * <p>仅当 {@code matrixcode.auth.require-sa-token=true} 时生效。预检请求放行给 CORS
         * 配置处理；实际业务请求必须能被 {@link RequestActorResolver} 解析出登录用户。</p>
         */
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            if (!authProperties.isRequireSaToken() || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
                return true;
            }
            actorResolver.resolve(request);
            if (authProperties.isSessionAutoRenewEnabled()) {
                saTokenActorSession.renewIfNeeded(authProperties.defaultTokenTtl(), authProperties.sessionRenewThreshold());
            }
            return true;
        }
    }
}
