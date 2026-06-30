package com.matrixcode.identity.api;

import com.matrixcode.identity.application.MatrixCodeAuthProperties;
import com.matrixcode.identity.application.SignedActorTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class RequestActorResolver {

    public static final String CURRENT_USER_HEADER = "X-MatrixCode-User-Id";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final SignedActorTokenService tokenService;
    private final MatrixCodeAuthProperties authProperties;
    private final SaTokenActorSession saTokenActorSession;

    @Autowired
    public RequestActorResolver(
            SignedActorTokenService tokenService,
            MatrixCodeAuthProperties authProperties,
            SaTokenActorSession saTokenActorSession
    ) {
        this.tokenService = tokenService;
        this.authProperties = authProperties;
        this.saTokenActorSession = saTokenActorSession;
    }

    public RequestActorResolver(SignedActorTokenService tokenService, MatrixCodeAuthProperties authProperties) {
        this(tokenService, authProperties, SaTokenActorSession.noop());
    }

    public RequestActorResolver() {
        var properties = new MatrixCodeAuthProperties();
        this.tokenService = new SignedActorTokenService(properties);
        this.authProperties = properties;
        this.saTokenActorSession = SaTokenActorSession.noop();
    }

    /**
     * 从 HTTP 请求头解析当前请求用户，用于需要服务端强制鉴权的接口。
     *
     * @param request 当前 HTTP 请求。
     * @return 归一化后的当前用户 ID。
     */
    public String resolve(HttpServletRequest request) {
        var headerUserId = request.getHeader(CURRENT_USER_HEADER);
        var bearerToken = bearerToken(request.getHeader(AUTHORIZATION_HEADER));
        var saTokenUserId = saTokenActorSession.currentUserId();
        if (saTokenUserId.isPresent()) {
            return resolveSaTokenActor(saTokenUserId.get(), headerUserId);
        }
        var bearerSaTokenUserId = saTokenActorSession.userIdForToken(bearerToken);
        if (bearerSaTokenUserId.isPresent()) {
            return resolveSaTokenActor(bearerSaTokenUserId.get(), headerUserId);
        }
        if (authProperties.isRequireSaToken()) {
            var message = bearerToken.isBlank() ? "缺少 Sa-Token 登录态" : "Sa-Token 登录态无效";
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
        }
        if (!bearerToken.isBlank()) {
            return resolveSignedActor(bearerToken, headerUserId);
        }
        if (authProperties.isRequireSignedActorToken()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少可信身份令牌");
        }
        return resolveHeaderActor(headerUserId);
    }

    private String resolveSaTokenActor(String tokenUserId, String headerUserId) {
        if (headerUserId != null && !headerUserId.isBlank() && !tokenUserId.equals(headerUserId.trim())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sa-Token 登录用户和请求用户不一致");
        }
        return tokenUserId;
    }

    private String resolveSignedActor(String token, String headerUserId) {
        try {
            var tokenUserId = tokenService.verify(token);
            if (headerUserId != null && !headerUserId.isBlank() && !tokenUserId.equals(headerUserId.trim())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "身份令牌和请求用户不一致");
            }
            return tokenUserId;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, exception.getMessage(), exception);
        }
    }

    private String resolveHeaderActor(String actorUserId) {
        if (actorUserId == null || actorUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少当前用户身份");
        }
        return actorUserId.trim();
    }

    private String bearerToken(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return "";
        }
        var normalized = authorization.trim();
        if (!normalized.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return "";
        }
        return normalized.substring(BEARER_PREFIX.length()).trim();
    }
}
