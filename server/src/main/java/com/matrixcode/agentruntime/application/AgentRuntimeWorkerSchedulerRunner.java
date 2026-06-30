package com.matrixcode.agentruntime.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Spring 定时适配器，用于生产环境周期性驱动 Agent Runtime Worker。
 *
 * <p>该组件只有在 `matrixcode.agent-runtime.worker-scheduler.enabled=true` 时注册。
 * 定时方法会捕获异常并写入日志，避免单次模型供应商、数据库或网络异常终止后续调度。</p>
 */
@Component
@ConditionalOnProperty(prefix = "matrixcode.agent-runtime.worker-scheduler", name = "enabled", havingValue = "true")
public class AgentRuntimeWorkerSchedulerRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRuntimeWorkerSchedulerRunner.class);

    private final AgentRuntimeWorkerSchedulerService schedulerService;

    public AgentRuntimeWorkerSchedulerRunner(AgentRuntimeWorkerSchedulerService schedulerService) {
        this.schedulerService = schedulerService;
    }

    /**
     * 按配置周期执行一次 Worker 调度。
     */
    @Scheduled(fixedDelayString = "${matrixcode.agent-runtime.worker-scheduler.fixed-delay-ms:10000}")
    public void runOnce() {
        try {
            var result = schedulerService.runOnce();
            if (result.ticked() && (!result.claimedRunId().isBlank() || result.expiredRunCount() > 0)) {
                LOGGER.info(
                        "Agent Runtime Worker 调度完成 projectId={} workerId={} expiredRuns={} claimedRunId={} modelExecuted={}",
                        result.projectId(),
                        result.workerId(),
                        result.expiredRunCount(),
                        result.claimedRunId(),
                        result.modelExecuted()
                );
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("Agent Runtime Worker 调度失败：{}", exception.getMessage(), exception);
        }
    }
}
