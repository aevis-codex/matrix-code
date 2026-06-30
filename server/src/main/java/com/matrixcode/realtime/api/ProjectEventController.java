package com.matrixcode.realtime.api;

import com.matrixcode.identity.api.ProjectRequestPermissionGuard;
import com.matrixcode.identity.api.RequestActorResolver;
import com.matrixcode.realtime.application.ProjectEventBus;
import com.matrixcode.realtime.domain.ProjectEvent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 项目事件读取和 SSE 订阅 API。
 *
 * <p>作用域：项目成员；场景：工作台实时同步、关键事件展示和多人协作刷新。</p>
 */
@RestController
@RequestMapping("/api/projects/{projectId}/events")
public class ProjectEventController {

    private final ProjectEventBus eventBus;
    private final ProjectRequestPermissionGuard requestPermissionGuard;

    public ProjectEventController(ProjectEventBus eventBus, ProjectRequestPermissionGuard requestPermissionGuard) {
        this.eventBus = eventBus;
        this.requestPermissionGuard = requestPermissionGuard;
    }

    /**
     * 读取项目最近事件。
     */
    @GetMapping
    public List<ProjectEvent> recent(@PathVariable String projectId, HttpServletRequest request) {
        requestPermissionGuard.assertProjectMember(request, projectId);
        return eventBus.recent(projectId);
    }

    /**
     * 建立项目事件 SSE 流。
     *
     * <p>作用域：项目成员；场景：浏览器 EventSource 无法设置自定义 header 时允许 query token 适配。</p>
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String projectId, HttpServletRequest request) {
        requestPermissionGuard.assertProjectMember(streamRequest(request), projectId);
        var emitter = new SseEmitter(0L);
        var subscriptionRef = new AtomicReference<AutoCloseable>();
        var subscription = eventBus.subscribe(projectId, event -> sendEvent(emitter, subscriptionRef, event));
        subscriptionRef.set(subscription);
        emitter.onCompletion(() -> closeQuietly(subscriptionRef.get()));
        emitter.onTimeout(() -> closeQuietly(subscriptionRef.get()));
        emitter.onError(ignored -> closeQuietly(subscriptionRef.get()));
        return emitter;
    }

    private void sendEvent(SseEmitter emitter, AtomicReference<AutoCloseable> subscriptionRef, ProjectEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.type())
                    .data(event));
        } catch (IOException | IllegalStateException ex) {
            closeQuietly(subscriptionRef.get());
            emitter.completeWithError(ex);
        }
    }

    private void closeQuietly(AutoCloseable subscription) {
        if (subscription == null) {
            return;
        }
        try {
            subscription.close();
        } catch (Exception ignored) {
        }
    }

    private HttpServletRequest streamRequest(HttpServletRequest request) {
        var queryActor = request.getParameter("actorUserId");
        var queryToken = request.getParameter("actorToken");
        if ((queryActor == null || queryActor.isBlank()) && (queryToken == null || queryToken.isBlank())) {
            return request;
        }
        return new EventStreamIdentityRequest(request, queryActor, queryToken);
    }

    /**
     * 原生 EventSource 不能设置自定义请求头；事件流只在这一层把 URL 身份参数适配为
     * 已有请求身份解析器识别的头，避免放宽其他敏感接口的 query token 入口。
     */
    private static final class EventStreamIdentityRequest extends HttpServletRequestWrapper {
        private final String queryActor;
        private final String queryToken;

        private EventStreamIdentityRequest(HttpServletRequest request, String queryActor, String queryToken) {
            super(request);
            this.queryActor = queryActor == null ? "" : queryActor.trim();
            this.queryToken = queryToken == null ? "" : queryToken.trim();
        }

        @Override
        public String getHeader(String name) {
            var headerValue = super.getHeader(name);
            if (headerValue != null && !headerValue.isBlank()) {
                return headerValue;
            }
            if (RequestActorResolver.CURRENT_USER_HEADER.equalsIgnoreCase(name) && !queryActor.isBlank()) {
                return queryActor;
            }
            if ("Authorization".equalsIgnoreCase(name) && !queryToken.isBlank()) {
                return "Bearer " + queryToken;
            }
            return headerValue;
        }
    }
}
