package com.matrixcode.execution.api;

import com.matrixcode.identity.api.ProjectRequestPermissionGuard;
import com.matrixcode.execution.application.ExecutionAgentProperties;
import com.matrixcode.execution.application.ExecutionGateway;
import com.matrixcode.execution.domain.AgentHeartbeat;
import com.matrixcode.execution.domain.ExecutionResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * 外部执行代理回调 API。
 *
 * <p>作用域：受控执行代理；场景：代理上报认证心跳和任务结果，服务端校验设备凭据、项目成员和防重放。</p>
 */
@RestController
@RequestMapping("/api/execution-agents")
public class ExecutionAgentController {

    private static final String AGENT_TOKEN_HEADER = "X-MatrixCode-Agent-Token";

    private final ExecutionGateway gateway;
    private final ProjectRequestPermissionGuard requestPermissionGuard;
    private final ExecutionAgentProperties properties;

    public ExecutionAgentController(
            ExecutionGateway gateway,
            ProjectRequestPermissionGuard requestPermissionGuard,
            ExecutionAgentProperties properties
    ) {
        this.gateway = Objects.requireNonNull(gateway, "gateway 不能为空");
        this.requestPermissionGuard = Objects.requireNonNull(requestPermissionGuard, "requestPermissionGuard 不能为空");
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
    }

    /**
     * 接收执行代理认证心跳。
     *
     * <p>作用域：执行代理；场景：证明代理仍持有共享凭据，并绑定项目成员身份。</p>
     */
    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(@RequestBody AgentHeartbeat heartbeat, HttpServletRequest request) {
        assertAgentToken(request);
        requestPermissionGuard.assertProjectMemberActor(request, heartbeat.projectId(), heartbeat.userId());
        gateway.heartbeat(heartbeat);
        return ResponseEntity.accepted().build();
    }

    /**
     * 接收执行代理任务结果。
     *
     * <p>作用域：执行代理；场景：在新鲜心跳存在时写入任务结果，并拒绝不同内容回放。</p>
     */
    @PostMapping("/results")
    public ResponseEntity<Void> report(@RequestBody ExecutionResult result, HttpServletRequest request) {
        assertAgentToken(request);
        var heartbeat = gateway.lastHeartbeat(result.agentId());
        if (heartbeat == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "执行代理尚未完成认证心跳");
        }
        if (!gateway.hasFreshHeartbeat(result.agentId(), properties.heartbeatTtl())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "执行代理认证心跳已过期");
        }
        requestPermissionGuard.assertProjectMemberActor(request, heartbeat.projectId(), heartbeat.userId());
        var outcome = gateway.report(result);
        if (outcome == ExecutionGateway.ReportOutcome.REPLAY_REJECTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "执行代理结果回放被拒绝");
        }
        return ResponseEntity.accepted().build();
    }

    /**
     * 校验执行代理设备级共享凭据。
     *
     * <p>未配置共享密钥时保持本地开发兼容；一旦配置，心跳和结果上报都必须提供匹配的
     * `X-MatrixCode-Agent-Token`，避免只依赖用户身份头伪造代理回调。</p>
     */
    private void assertAgentToken(HttpServletRequest request) {
        var acceptedSecrets = properties.acceptedSharedSecrets();
        if (acceptedSecrets.isEmpty()) {
            return;
        }
        var providedToken = request.getHeader(AGENT_TOKEN_HEADER);
        if (providedToken == null || providedToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少执行代理凭据");
        }
        var provided = providedToken.trim();
        var matched = false;
        for (var secret : acceptedSecrets) {
            matched |= sameToken(secret, provided);
        }
        if (!matched) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "执行代理凭据不正确");
        }
    }

    private boolean sameToken(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * 将非法回调请求统一转换为 400。
     */
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Void> rejectInvalidReport() {
        return ResponseEntity.badRequest().build();
    }
}
