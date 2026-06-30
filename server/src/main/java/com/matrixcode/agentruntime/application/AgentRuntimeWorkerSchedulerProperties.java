package com.matrixcode.agentruntime.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent Runtime Worker 后台调度配置。
 *
 * <p>调度器默认关闭，避免本地开发或测试环境无意消费 `QUEUED` 运行。生产环境确认模型供应商、
 * Sa-Token、成员身份和审计链路均可用后，可通过环境变量开启，并按需启用受控模型步骤。</p>
 */
@Component
@ConfigurationProperties(prefix = "matrixcode.agent-runtime.worker-scheduler")
public class AgentRuntimeWorkerSchedulerProperties {

    private boolean enabled = false;
    private String projectId = "demo";
    private String workerId = "matrixcode-worker";
    private boolean executeModelRequest = false;
    private long fixedDelayMs = 10000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = textOr(projectId, "demo");
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = textOr(workerId, "matrixcode-worker");
    }

    public boolean isExecuteModelRequest() {
        return executeModelRequest;
    }

    public void setExecuteModelRequest(boolean executeModelRequest) {
        this.executeModelRequest = executeModelRequest;
    }

    public long getFixedDelayMs() {
        return fixedDelayMs;
    }

    public void setFixedDelayMs(long fixedDelayMs) {
        this.fixedDelayMs = fixedDelayMs < 1000 ? 10000 : fixedDelayMs;
    }

    private String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
