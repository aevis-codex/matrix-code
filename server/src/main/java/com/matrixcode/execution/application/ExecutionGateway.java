package com.matrixcode.execution.application;

import com.matrixcode.execution.domain.AgentHeartbeat;
import com.matrixcode.execution.domain.ExecutionResult;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ExecutionGateway {

    public enum ReportOutcome {
        RECORDED,
        DUPLICATE,
        REPLAY_REJECTED
    }

    private final Map<String, AgentHeartbeat> heartbeats = new ConcurrentHashMap<>();
    private final Map<String, Instant> heartbeatReceivedAt = new ConcurrentHashMap<>();
    private final Map<String, ExecutionResult> results = new ConcurrentHashMap<>();
    private final Clock clock;

    public ExecutionGateway() {
        this(Clock.systemUTC());
    }

    public ExecutionGateway(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    public void heartbeat(AgentHeartbeat heartbeat) {
        heartbeats.put(heartbeat.agentId(), heartbeat);
        heartbeatReceivedAt.put(heartbeat.agentId(), clock.instant());
    }

    public AgentHeartbeat lastHeartbeat(String agentId) {
        return heartbeats.get(agentId);
    }

    /**
     * 判断代理最近一次已认证心跳是否仍在有效期内。
     *
     * <p>结果上报依赖该检查，避免代理长时间离线后复用旧心跳上下文伪造任务结果。</p>
     */
    public boolean hasFreshHeartbeat(String agentId, Duration ttl) {
        var receivedAt = heartbeatReceivedAt.get(agentId);
        if (receivedAt == null) {
            return false;
        }
        return !receivedAt.plus(ttl).isBefore(clock.instant());
    }

    /**
     * 记录执行代理上报的任务结果，并阻止同一任务 ID 被不同结果覆盖。
     *
     * <p>执行代理回调可能因为网络重试重复发送完全相同的 payload，这种情况返回 `DUPLICATE`
     * 并保持状态不变；如果同一 `taskId` 携带了不同状态、代理或摘要，则视为可疑回放并拒绝覆盖已有结果。</p>
     */
    public ReportOutcome report(ExecutionResult result) {
        var existing = results.putIfAbsent(result.taskId(), result);
        if (existing == null) {
            return ReportOutcome.RECORDED;
        }
        return existing.equals(result) ? ReportOutcome.DUPLICATE : ReportOutcome.REPLAY_REJECTED;
    }

    public ExecutionResult resultOf(String taskId) {
        return results.get(taskId);
    }
}
