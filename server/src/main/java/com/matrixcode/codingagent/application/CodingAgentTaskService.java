package com.matrixcode.codingagent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.agentruntime.application.AgentRuntimeService;
import com.matrixcode.agentruntime.domain.AgentRunStatus;
import com.matrixcode.codingagent.domain.CodingAgentStep;
import com.matrixcode.codingagent.domain.CodingAgentStepType;
import com.matrixcode.codingagent.domain.CodingAgentTask;
import com.matrixcode.codingagent.domain.CodingAgentTaskStatus;
import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.roleagent.application.RoleAgentConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class CodingAgentTaskService {

    private final RoleAgentConfigService roleAgentConfigService;
    private final AgentRuntimeService agentRuntimeService;

    public CodingAgentTaskService(RoleAgentConfigService roleAgentConfigService) {
        this(roleAgentConfigService, new AgentRuntimeService(Optional.empty(), new ObjectMapper(), Clock.systemUTC()));
    }

    @Autowired
    public CodingAgentTaskService(RoleAgentConfigService roleAgentConfigService, AgentRuntimeService agentRuntimeService) {
        this.roleAgentConfigService = roleAgentConfigService;
        this.agentRuntimeService = agentRuntimeService;
    }

    public CodingAgentTask plan(String projectId, ModelRole role, String goal, String workspaceId) {
        return plan(projectId, role, goal, workspaceId, "system");
    }

    /**
     * 规划一次编码智能体任务。
     *
     * <p>方法会校验角色配置是否启用，生成固定执行步骤，并把规划结果写入 Agent 运行记录。
     * 写入运行记录使用任务 ID 作为运行 ID，便于后续执行准备、Patch 和交接事件继续串联同一条时间线。</p>
     */
    public CodingAgentTask plan(String projectId, ModelRole role, String goal, String workspaceId, String actorUserId) {
        projectId = requireText(projectId, "项目编号不能为空");
        goal = requireText(goal, "编码目标不能为空");
        workspaceId = requireText(workspaceId, "工作区编号不能为空");
        if (role == null) {
            throw new IllegalArgumentException("角色不能为空");
        }
        var config = roleAgentConfigService.require(projectId, role);
        if (!config.enabled()) {
            throw new IllegalArgumentException("角色智能体未启用：" + config.displayName());
        }
        var task = new CodingAgentTask(
                UUID.randomUUID().toString(),
                projectId,
                role,
                goal,
                workspaceId,
                CodingAgentTaskStatus.PLANNED,
                Instant.now(),
                steps(goal)
        );
        agentRuntimeService.saveRun(
                task.taskId(),
                projectId,
                role,
                config.agentKind(),
                actorUserId,
                config.providerId(),
                config.model(),
                AgentRunStatus.QUEUED,
                goal,
                "编码任务已规划",
                null,
                null
        );
        agentRuntimeService.appendEvent(task.taskId(), projectId, "TASK_PLANNED", "编码任务已规划", Map.of(
                "workspaceId", workspaceId,
                "goal", goal,
                "stepCount", task.steps().size()
        ));
        return task;
    }

    private List<CodingAgentStep> steps(String goal) {
        return List.of(
                new CodingAgentStep(
                        1,
                        CodingAgentStepType.CONTEXT_RECALL,
                        "召回项目上下文",
                        "围绕目标召回项目图谱、需求、相关代码和最近验证证据：" + goal,
                        "model-gateway.vector-context",
                        false
                ),
                new CodingAgentStep(
                        2,
                        CodingAgentStepType.PLAN_REVIEW,
                        "生成并审查实现计划",
                        "产出小步计划，确认影响文件、测试范围、回滚方式和安全边界。",
                        "model-gateway.model-requests",
                        false
                ),
                new CodingAgentStep(
                        3,
                        CodingAgentStepType.FILE_REVIEW,
                        "读取相关文件",
                        "只读取授权工作区内与任务相关的源码、测试和文档。",
                        "local-execution.files.read",
                        false
                ),
                new CodingAgentStep(
                        4,
                        CodingAgentStepType.CODE_EDIT,
                        "执行最小代码修改",
                        "按计划修改代码，并保持改动集中、可审查、可回滚。",
                        "local-execution.files.write",
                        true
                ),
                new CodingAgentStep(
                        5,
                        CodingAgentStepType.TEST_COMMAND,
                        "运行聚焦测试",
                        "通过本地执行代理运行与改动相关的 Maven、Vitest 或脚本验证命令。",
                        "local-execution.commands",
                        true
                ),
                new CodingAgentStep(
                        6,
                        CodingAgentStepType.DIFF_REVIEW,
                        "审查 Git diff",
                        "生成 diff 摘要，检查是否包含无关改动、密钥或风险文件。",
                        "local-execution.git-diff",
                        false
                ),
                new CodingAgentStep(
                        7,
                        CodingAgentStepType.HANDOFF,
                        "交付与回溯",
                        "更新阶段计划、项目图谱和验证记录，形成可继续扩展的交接结果。",
                        "obsidian.project-graph",
                        false
                )
        );
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
