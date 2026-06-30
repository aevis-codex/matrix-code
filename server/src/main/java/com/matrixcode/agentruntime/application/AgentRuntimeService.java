package com.matrixcode.agentruntime.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.agentruntime.domain.AgentRunEventRecord;
import com.matrixcode.agentruntime.domain.AgentRunRecoveryPlan;
import com.matrixcode.agentruntime.domain.AgentRunRecord;
import com.matrixcode.agentruntime.domain.AgentRunStatus;
import com.matrixcode.modelgateway.domain.ModelRole;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class AgentRuntimeService {

    private static final String SYSTEM_ACTOR = "system";
    private static final Duration CLAIM_LEASE_DURATION = Duration.ofMinutes(15);
    private static final String EXPIRED_LEASE_FAILURE_SUMMARY = "Worker 租约已过期，运行被系统回收";

    private final Optional<AgentRuntimeRepository> repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public AgentRuntimeService(ObjectProvider<AgentRuntimeRepository> repository, ObjectMapper objectMapper) {
        this(Optional.ofNullable(repository.getIfAvailable()), objectMapper, Clock.systemUTC());
    }

    public AgentRuntimeService(
            Optional<AgentRuntimeRepository> repository,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.repository = repository == null ? Optional.empty() : repository;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * 保存或更新一次 Agent 运行主记录。
     *
     * <p>该方法是业务层统一入口，负责把角色、模型、目标和运行状态归一为正式审计记录。
     * 当正式仓储不存在时（默认文件模式），方法仍返回构造出的运行对象，但不会产生外部副作用；
     * 当正式仓储存在时，持久化失败会向上抛出，避免生产环境静默丢失 Agent 审计链路。</p>
     *
     * @param runId 运行 ID，通常与编码任务 ID 相同；为空时自动生成。
     * @param projectId 项目 ID。
     * @param role 角色枚举，用于固化历史角色归属。
     * @param agentKind Agent 类型，例如 coding、testing。
     * @param actorUserId 操作者用户 ID；为空时归一为 system。
     * @param providerId 模型供应商 ID。
     * @param modelName 模型名称。
     * @param status 运行状态。
     * @param goal 运行目标。
     * @param summary 当前状态摘要。
     * @param startedAt 开始时间；可为空。
     * @param finishedAt 完成时间；可为空。
     * @return 已归一化的运行主记录。
     */
    public AgentRunRecord saveRun(
            String runId,
            String projectId,
            ModelRole role,
            String agentKind,
            String actorUserId,
            String providerId,
            String modelName,
            AgentRunStatus status,
            String goal,
            String summary,
            Instant startedAt,
            Instant finishedAt
    ) {
        return saveRun(
                runId,
                projectId,
                role,
                agentKind,
                actorUserId,
                providerId,
                modelName,
                status,
                goal,
                summary,
                "",
                false,
                "",
                startedAt,
                finishedAt
        );
    }

    /**
     * 保存或更新带失败恢复元数据的 Agent 运行主记录。
     *
     * <p>该重载是失败恢复和重试链路的基础入口。`failureSummary` 只保存短摘要，
     * 不应包含完整异常堆栈、完整 prompt、API Key、数据库密码或大体积工具输出。
     * `retryOfRunId` 为空时表示原始运行；非空时表示本次运行由某次失败恢复而来。</p>
     *
     * @param failureSummary 失败短摘要；非失败状态允许为空。
     * @param retryable 当前失败是否可基于相同目标恢复重试。
     * @param retryOfRunId 重试来源运行 ID；原始运行为空。
     * @return 已归一化并写入仓储的运行主记录。
     */
    public AgentRunRecord saveRun(
            String runId,
            String projectId,
            ModelRole role,
            String agentKind,
            String actorUserId,
            String providerId,
            String modelName,
            AgentRunStatus status,
            String goal,
            String summary,
            String failureSummary,
            boolean retryable,
            String retryOfRunId,
            Instant startedAt,
            Instant finishedAt
    ) {
        if (role == null) {
            throw new IllegalArgumentException("角色不能为空");
        }
        var now = clock.instant();
        var normalizedStatus = status == null ? AgentRunStatus.QUEUED : status;
        var run = new AgentRunRecord(
                textOrGenerated(runId),
                requireText(projectId, "项目编号不能为空"),
                role.name(),
                requireText(agentKind, "Agent 类型不能为空"),
                actorOrSystem(actorUserId),
                requireText(providerId, "模型供应商不能为空"),
                requireText(modelName, "模型名称不能为空"),
                normalizedStatus,
                requireText(goal, "运行目标不能为空"),
                trimToEmpty(summary),
                trimToEmpty(failureSummary),
                retryable,
                trimToEmpty(retryOfRunId),
                now,
                normalizeStartedAt(normalizedStatus, startedAt, now),
                normalizeFinishedAt(normalizedStatus, finishedAt, now),
                now
        );
        repository.ifPresent(agentRuntimeRepository -> agentRuntimeRepository.saveRun(run));
        return run;
    }

    /**
     * 将一次 Agent 运行标记为运行中，并追加标准化开始事件。
     *
     * <p>该方法用于替代调用方散落的 `saveRun(...RUNNING...)` 写法。它同时维护主记录和
     * `RUN_STARTED` 时间线事件，保证运行中心、审计回放和后续自动恢复逻辑可以基于同一种生命周期边界工作。
     * 事件 payload 只写入摘要、角色、Agent 类型、供应商和模型，不写入完整 prompt、工具输出或任何密钥。</p>
     */
    public AgentRunRecord markRunning(
            String runId,
            String projectId,
            ModelRole role,
            String agentKind,
            String actorUserId,
            String providerId,
            String modelName,
            String goal,
            String summary
    ) {
        var run = saveRun(
                runId,
                projectId,
                role,
                agentKind,
                actorUserId,
                providerId,
                modelName,
                AgentRunStatus.RUNNING,
                goal,
                summary,
                null,
                null
        );
        appendEvent(
                run.id(),
                run.projectId(),
                "RUN_STARTED",
                "运行开始",
                Map.of(
                        "summary", run.summary(),
                        "providerId", run.providerId(),
                        "modelName", run.modelName(),
                        "role", run.roleKey(),
                        "agentKind", run.agentKind()
                )
        );
        return run;
    }

    /**
     * 将一次 Agent 运行标记为成功，并追加标准化成功事件。
     *
     * <p>该方法用于替代调用方散落的 `saveRun(...SUCCEEDED...)` 写法。它统一落库成功态主记录、
     * 归一开始/完成时间，并写入 `RUN_SUCCEEDED` 事件。调用方仍可在该事件之后追加业务事件和工具 trace，
     * 从而形成“生命周期事件 -> 业务结果 -> 工具证据”的可审计顺序。</p>
     */
    public AgentRunRecord markSucceeded(
            String runId,
            String projectId,
            ModelRole role,
            String agentKind,
            String actorUserId,
            String providerId,
            String modelName,
            String goal,
            String summary
    ) {
        var run = saveRun(
                runId,
                projectId,
                role,
                agentKind,
                actorUserId,
                providerId,
                modelName,
                AgentRunStatus.SUCCEEDED,
                goal,
                summary,
                null,
                null
        );
        appendEvent(
                run.id(),
                run.projectId(),
                "RUN_SUCCEEDED",
                "运行成功",
                Map.of(
                        "summary", run.summary(),
                        "providerId", run.providerId(),
                        "modelName", run.modelName(),
                        "role", run.roleKey(),
                        "agentKind", run.agentKind()
                )
        );
        return run;
    }

    /**
     * 将一次 Agent 运行标记为失败，并追加结构化失败事件。
     *
     * <p>该方法面向编码智能体、测试智能体和后续工具执行器复用：调用方只传失败摘要和可重试判断，
     * 服务层负责固化主记录、结束时间和 `RUN_FAILED` 事件。事件 payload 刻意保持短小，
     * 避免把完整 prompt、完整异常堆栈或敏感配置写入审计流。</p>
     */
    public AgentRunRecord markFailed(
            String runId,
            String projectId,
            ModelRole role,
            String agentKind,
            String actorUserId,
            String providerId,
            String modelName,
            String goal,
            String failureSummary,
            boolean retryable,
            String retryOfRunId
    ) {
        var normalizedFailureSummary = failureSummaryOrDefault(failureSummary);
        var normalizedRetryOfRunId = trimToEmpty(retryOfRunId);
        var run = saveRun(
                runId,
                projectId,
                role,
                agentKind,
                actorUserId,
                providerId,
                modelName,
                AgentRunStatus.FAILED,
                goal,
                normalizedFailureSummary,
                normalizedFailureSummary,
                retryable,
                normalizedRetryOfRunId,
                null,
                null
        );
        appendEvent(
                run.id(),
                run.projectId(),
                "RUN_FAILED",
                "运行失败",
                Map.of(
                        "failureSummary", run.failureSummary(),
                        "retryable", run.retryable(),
                        "retryOfRunId", run.retryOfRunId(),
                        "providerId", run.providerId(),
                        "modelName", run.modelName()
                )
        );
        return run;
    }

    /**
     * 生成单次失败运行的恢复计划。
     *
     * <p>该方法只读取运行主记录并给出可恢复判断，不写入数据库，也不触发模型、工具或后台执行。
     * 计划结果面向运行中心和后续调度器使用，阻塞原因必须保持用户可读，便于阶段验收时定位为什么不能重试。</p>
     *
     * @param projectId 当前项目 ID，用于防止跨项目读取恢复来源。
     * @param runId 失败运行 ID。
     * @return 恢复计划，包含是否可重试、阻塞原因和建议动作。
     */
    public AgentRunRecoveryPlan recoveryPlan(String projectId, String runId) {
        var normalizedProjectId = requireText(projectId, "项目编号不能为空");
        var normalizedRunId = requireText(runId, "运行编号不能为空");
        var sourceRun = findSourceRun(normalizedRunId)
                .filter(run -> run.projectId().equals(normalizedProjectId));
        if (sourceRun.isEmpty()) {
            return AgentRunRecoveryPlan.blocked(normalizedRunId, "运行不存在或不属于当前项目", null);
        }
        return recoveryPlanFor(sourceRun.get());
    }

    /**
     * 从可重试失败运行创建新的排队运行。
     *
     * <p>该方法只创建 `QUEUED` 主记录和恢复审计事件，不消费队列、不执行命令、不调用模型、不应用 Patch。
     * 新运行通过 `retryOfRunId` 指向失败来源，源运行追加 `RUN_RETRY_REQUESTED`，新运行追加
     * `RUN_RETRY_QUEUED`，从而保证运行中心能完整回放恢复链路。</p>
     *
     * @param projectId 当前项目 ID。
     * @param runId 失败来源运行 ID。
     * @param actorUserId 触发重试的操作者；为空时归一为 system。
     * @return 新创建的排队运行。
     */
    public AgentRunRecord queueRetry(String projectId, String runId, String actorUserId) {
        var normalizedProjectId = requireText(projectId, "项目编号不能为空");
        var normalizedRunId = requireText(runId, "运行编号不能为空");
        var sourceRun = findSourceRun(normalizedRunId)
                .filter(run -> run.projectId().equals(normalizedProjectId))
                .orElseThrow(() -> new IllegalStateException("运行不存在或不属于当前项目"));
        var plan = recoveryPlanFor(sourceRun);
        if (!plan.canRetry()) {
            throw new IllegalStateException(plan.blockedReason());
        }
        var retryRun = saveRun(
                UUID.randomUUID().toString(),
                sourceRun.projectId(),
                parseModelRole(sourceRun.roleKey()),
                sourceRun.agentKind(),
                actorUserId,
                sourceRun.providerId(),
                sourceRun.modelName(),
                AgentRunStatus.QUEUED,
                sourceRun.goal(),
                "等待从失败运行恢复",
                sourceRun.failureSummary(),
                false,
                sourceRun.id(),
                null,
                null
        );
        appendEvent(
                sourceRun.id(),
                sourceRun.projectId(),
                "RUN_RETRY_REQUESTED",
                "已创建恢复重试",
                Map.of(
                        "retryRunId", retryRun.id(),
                        "actorUserId", retryRun.actorUserId(),
                        "failureSummary", sourceRun.failureSummary()
                )
        );
        appendEvent(
                retryRun.id(),
                retryRun.projectId(),
                "RUN_RETRY_QUEUED",
                "恢复重试已排队",
                Map.of(
                        "sourceRunId", sourceRun.id(),
                        "failureSummary", retryRun.failureSummary(),
                        "providerId", retryRun.providerId(),
                        "modelName", retryRun.modelName(),
                        "role", retryRun.roleKey(),
                        "agentKind", retryRun.agentKind()
                )
        );
        return retryRun;
    }

    /**
     * 将排队中的 Agent 运行认领为运行中。
     *
     * <p>该方法是恢复队列和后续 Worker 的受控入口。它只把已有 `QUEUED` 运行推进到 `RUNNING`，
     * 并写入 `RUN_CLAIMED` 和 `RUN_STARTED` 事件；不会调用模型、执行本地命令、写文件或应用 Patch。
     * 认领后保留原始操作者、创建时间、失败摘要和重试来源，`actorUserId` 仅作为认领人写入事件 payload。</p>
     *
     * @param projectId 当前项目 ID。
     * @param runId 被认领的排队运行 ID。
     * @param actorUserId 认领人用户 ID；为空时归一为 system。
     * @return 更新为运行中的 Agent 运行主记录。
     */
    public AgentRunRecord claimQueuedRun(String projectId, String runId, String actorUserId) {
        var normalizedProjectId = requireText(projectId, "项目编号不能为空");
        var normalizedRunId = requireText(runId, "运行编号不能为空");
        var sourceRun = findSourceRun(normalizedRunId)
                .filter(run -> run.projectId().equals(normalizedProjectId))
                .orElseThrow(() -> new IllegalStateException("运行不存在或不属于当前项目"));
        if (sourceRun.status() != AgentRunStatus.QUEUED) {
            throw new IllegalStateException("只有排队运行可以认领");
        }
        var claimedBy = actorOrSystem(actorUserId);
        var now = clock.instant();
        var claimed = new AgentRunRecord(
                sourceRun.id(),
                sourceRun.projectId(),
                sourceRun.roleKey(),
                sourceRun.agentKind(),
                sourceRun.actorUserId(),
                sourceRun.providerId(),
                sourceRun.modelName(),
                AgentRunStatus.RUNNING,
                sourceRun.goal(),
                "运行已认领",
                sourceRun.failureSummary(),
                sourceRun.retryable(),
                sourceRun.retryOfRunId(),
                sourceRun.createdAt(),
                now,
                null,
                now,
                claimedBy,
                now,
                now.plus(CLAIM_LEASE_DURATION)
        );
        repository.ifPresent(agentRuntimeRepository -> agentRuntimeRepository.saveRun(claimed));
        appendClaimEvents(claimed, sourceRun.status().name(), claimedBy);
        return claimed;
    }

    /**
     * 从项目队列中受控认领下一条排队运行。
     *
     * <p>该入口面向后续 Worker 自动消费。服务层只计算认领租约、调用仓储条件更新并追加审计事件；
     * 不调用模型、不执行命令、不写文件、不应用 Patch。队列为空或并发竞争失败时返回空。</p>
     *
     * @param projectId 当前项目 ID。
     * @param actorUserId 认领人或 Worker ID；为空时归一为 system。
     * @return 成功认领时返回运行中记录，否则返回空。
     */
    public Optional<AgentRunRecord> claimNextQueuedRun(String projectId, String actorUserId) {
        var normalizedProjectId = requireText(projectId, "项目编号不能为空");
        var claimedBy = actorOrSystem(actorUserId);
        var now = clock.instant();
        var claimExpiresAt = now.plus(CLAIM_LEASE_DURATION);
        var claimed = repository.flatMap(agentRuntimeRepository -> agentRuntimeRepository.claimNextQueuedRun(
                normalizedProjectId,
                claimedBy,
                now,
                claimExpiresAt
        ));
        claimed.ifPresent(run -> appendClaimEvents(run, AgentRunStatus.QUEUED.name(), claimedBy));
        return claimed;
    }

    /**
     * 续期一次运行中的 Agent 运行租约。
     *
     * <p>该方法面向真实 Worker 的心跳调用。服务层只计算新的租约到期时间、委托仓储做条件更新，
     * 并在成功时追加 `RUN_LEASE_RENEWED` 低敏审计事件；不会调用模型、执行命令、写文件或应用 Patch。
     * 如果运行不存在、不是 `RUNNING` 或认领人不匹配，返回空。</p>
     *
     * @param projectId 当前项目 ID。
     * @param runId 运行 ID。
     * @param actorUserId 当前认领人或 Worker ID；为空时归一为 system。
     * @return 成功续期时返回更新后的运行记录，否则返回空。
     */
    public Optional<AgentRunRecord> renewClaimLease(String projectId, String runId, String actorUserId) {
        var normalizedProjectId = requireText(projectId, "项目编号不能为空");
        var normalizedRunId = requireText(runId, "运行编号不能为空");
        var claimedBy = actorOrSystem(actorUserId);
        var now = clock.instant();
        var claimExpiresAt = now.plus(CLAIM_LEASE_DURATION);
        var renewed = repository.flatMap(agentRuntimeRepository -> agentRuntimeRepository.renewClaimLease(
                normalizedProjectId,
                normalizedRunId,
                claimedBy,
                now,
                claimExpiresAt
        ));
        renewed.ifPresent(run -> appendEvent(
                run.id(),
                run.projectId(),
                "RUN_LEASE_RENEWED",
                "运行租约已续期",
                Map.of(
                        "claimedBy", claimedBy,
                        "renewedAt", now.toString(),
                        "claimExpiresAt", claimExpiresAt.toString()
                )
        ));
        return renewed;
    }

    /**
     * 回收租约过期的运行中 Agent 运行。
     *
     * <p>该方法用于 Worker tick 或运维脚本清理卡住的 `RUNNING` 运行。仓储层负责并发安全条件更新；
     * 服务层只追加 `RUN_LEASE_EXPIRED` 和 `RUN_FAILED` 事件，并把运行保持为可重试失败，便于既有恢复队列继续处理。</p>
     *
     * @param projectId 当前项目 ID。
     * @param limit 单次最多回收数量；小于 1 时直接返回空。
     * @return 本次成功回收的运行记录。
     */
    public List<AgentRunRecord> expireRunningLeases(String projectId, int limit) {
        var normalizedProjectId = requireText(projectId, "项目编号不能为空");
        if (limit < 1) {
            return List.of();
        }
        var now = clock.instant();
        var expiredRuns = repository
                .map(agentRuntimeRepository -> agentRuntimeRepository.expireRunningLeases(
                        normalizedProjectId,
                        now,
                        limit,
                        EXPIRED_LEASE_FAILURE_SUMMARY
                ))
                .orElseGet(List::of);
        expiredRuns.forEach(run -> appendExpiredLeaseEvents(run, now));
        return expiredRuns;
    }

    /**
     * 追加一次标准化 Agent 工具调用 trace。
     *
     * <p>该入口用于把模型请求、文件写入、测试命令、Git diff、文档生成等工具动作归一为同一种
     * `TOOL_TRACE` 事件。调用方只应传短摘要和低敏 metadata；完整 prompt、完整文件内容、完整命令输出、
     * API Key、数据库密码等敏感或高体积内容必须留在受控系统内，不进入运行事件。</p>
     *
     * @param toolName 工具命名空间，例如 local-execution.commands。
     * @param action 工具动作，例如 submit-test-command。
     * @param status 工具动作结果或状态。
     * @param referenceId 外部引用 ID，例如本地任务 ID、文档 ID 或工作区 ID；允许为空。
     * @param summary 面向审计和运行中心展示的短摘要。
     * @param metadata 额外低敏结构化元数据；为空时写入空对象。
     * @return 已写入事件时间线的工具 trace。
     */
    public AgentRunEventRecord appendToolTrace(
            String runId,
            String projectId,
            String toolName,
            String action,
            String status,
            String referenceId,
            String summary,
            Map<String, ?> metadata
    ) {
        return appendEvent(
                runId,
                projectId,
                "TOOL_TRACE",
                "工具调用 trace",
                Map.of(
                        "toolName", requireText(toolName, "工具名称不能为空"),
                        "action", requireText(action, "工具动作不能为空"),
                        "status", requireText(status, "工具状态不能为空"),
                        "referenceId", trimToEmpty(referenceId),
                        "summary", toolSummaryOrDefault(summary),
                        "metadata", metadata == null ? Map.of() : metadata
                )
        );
    }

    private void appendClaimEvents(AgentRunRecord claimed, String previousStatus, String claimedBy) {
        appendEvent(
                claimed.id(),
                claimed.projectId(),
                "RUN_CLAIMED",
                "运行已认领",
                Map.of(
                        "claimedBy", claimedBy,
                        "previousStatus", previousStatus,
                        "retryOfRunId", claimed.retryOfRunId(),
                        "providerId", claimed.providerId(),
                        "modelName", claimed.modelName()
                )
        );
        appendEvent(
                claimed.id(),
                claimed.projectId(),
                "RUN_STARTED",
                "运行开始",
                Map.of(
                        "summary", claimed.summary(),
                        "providerId", claimed.providerId(),
                        "modelName", claimed.modelName(),
                        "role", claimed.roleKey(),
                        "agentKind", claimed.agentKind()
                )
        );
        appendEvent(
                claimed.id(),
                claimed.projectId(),
                "RUN_LEASED",
                "运行租约已建立",
                Map.of(
                        "claimedBy", claimedBy,
                        "claimedAt", claimed.claimedAt() == null ? "" : claimed.claimedAt().toString(),
                        "claimExpiresAt", claimed.claimExpiresAt() == null ? "" : claimed.claimExpiresAt().toString()
                )
        );
    }

    private void appendExpiredLeaseEvents(AgentRunRecord run, Instant expiredAt) {
        appendEvent(
                run.id(),
                run.projectId(),
                "RUN_LEASE_EXPIRED",
                "运行租约已过期",
                Map.of(
                        "claimedBy", run.claimedByUserId() == null ? "" : run.claimedByUserId(),
                        "claimExpiresAt", run.claimExpiresAt() == null ? "" : run.claimExpiresAt().toString(),
                        "expiredAt", expiredAt.toString()
                )
        );
        appendEvent(
                run.id(),
                run.projectId(),
                "RUN_FAILED",
                "运行失败",
                Map.of(
                        "failureSummary", run.failureSummary(),
                        "retryable", run.retryable(),
                        "retryOfRunId", run.retryOfRunId(),
                        "providerId", run.providerId(),
                        "modelName", run.modelName()
                )
        );
    }

    /**
     * 追加一次兼容旧调用方的模型网关请求 trace。
     *
     * <p>旧入口只记录 requestId、供应商、模型和缓存策略等低敏摘要。第 64 阶段后的新调用方
     * 应优先使用包含 Prompt 分区参数的重载，避免运行时间线缺少稳定前缀分区治理线索。</p>
     *
     * @param requestId 模型请求 ID，用于从运行时间线跳转到模型请求记录。
     * @param providerId 模型供应商 ID。
     * @param modelName 模型名称。
     * @param cacheSource 缓存用量来源，通常为 PROVIDER 或 ESTIMATED。
     * @param stablePrefixHash 稳定平台前缀指纹；允许为空。
     * @param cachePolicyId 缓存策略 ID；允许为空。
     * @param volatileSuffixStrategy 动态后缀策略 ID；允许为空。
     * @return 已写入事件时间线的模型请求 trace。
     */
    public AgentRunEventRecord appendModelRequestTrace(
            String runId,
            String projectId,
            String requestId,
            String providerId,
            String modelName,
            String cacheSource,
            String stablePrefixHash,
            String cachePolicyId,
            String volatileSuffixStrategy
    ) {
        return appendModelRequestTrace(
                runId,
                projectId,
                requestId,
                providerId,
                modelName,
                cacheSource,
                stablePrefixHash,
                cachePolicyId,
                volatileSuffixStrategy,
                "",
                "",
                0,
                0
        );
    }

    /**
     * 追加一次包含 Prompt 分区治理元数据的模型网关请求 trace。
     *
     * <p>这是模型网关到 Agent Runtime 的标准桥接入口。它只记录 requestId、供应商、模型、
     * 缓存策略和 Prompt 分区指纹等低敏摘要，便于复盘一次 Agent 运行的模型调用成本和缓存命中情况；
     * 完整 prompt、模型响应正文、工具输出、向量召回正文、API Key 和数据库密码都不得通过该入口写入运行事件。</p>
     *
     * @param requestId 模型请求 ID，用于从运行时间线跳转到模型请求记录。
     * @param providerId 模型供应商 ID。
     * @param modelName 模型名称。
     * @param cacheSource 缓存用量来源，通常为 PROVIDER 或 ESTIMATED。
     * @param stablePrefixHash 稳定平台前缀指纹；允许为空。
     * @param cachePolicyId 缓存策略 ID；允许为空。
     * @param volatileSuffixStrategy 动态后缀策略 ID；允许为空。
     * @param promptPartitionPolicyId Prompt 分区策略 ID；允许为空。
     * @param promptPartitionFingerprint Prompt 分区结构指纹；允许为空。
     * @param stablePartitionCount 稳定分区数量。
     * @param volatilePartitionCount 动态分区数量。
     * @return 已写入事件时间线的模型请求 trace。
     */
    public AgentRunEventRecord appendModelRequestTrace(
            String runId,
            String projectId,
            String requestId,
            String providerId,
            String modelName,
            String cacheSource,
            String stablePrefixHash,
            String cachePolicyId,
            String volatileSuffixStrategy,
            String promptPartitionPolicyId,
            String promptPartitionFingerprint,
            int stablePartitionCount,
            int volatilePartitionCount
    ) {
        return appendToolTrace(
                runId,
                projectId,
                "model-gateway.model-requests",
                "complete-model-request",
                "COMPLETED",
                requireText(requestId, "模型请求编号不能为空"),
                "模型请求已完成",
                Map.of(
                        "providerId", requireText(providerId, "模型供应商不能为空"),
                        "modelName", requireText(modelName, "模型名称不能为空"),
                        "cacheSource", cacheSourceOrUnknown(cacheSource),
                        "stablePrefixHash", trimToEmpty(stablePrefixHash),
                        "cachePolicyId", trimToEmpty(cachePolicyId),
                        "volatileSuffixStrategy", trimToEmpty(volatileSuffixStrategy),
                        "promptPartitionPolicyId", trimToEmpty(promptPartitionPolicyId),
                        "promptPartitionFingerprint", trimToEmpty(promptPartitionFingerprint),
                        "stablePartitionCount", Math.max(0, stablePartitionCount),
                        "volatilePartitionCount", Math.max(0, volatilePartitionCount)
                )
        );
    }

    /**
     * 追加一次 Agent 运行事件。
     *
     * <p>事件 payload 只接收结构化键值数据，并序列化为紧凑 JSON；调用方不应传入密钥、完整文件内容、
     * 完整 Prompt 等敏感或高体积内容。仓储不存在时该方法无副作用，便于文件模式继续运行。</p>
     *
     * @param runId 运行 ID。
     * @param projectId 项目 ID。
     * @param eventType 事件类型，使用大写下划线风格。
     * @param eventTitle 面向用户的事件标题。
     * @param payload 复盘所需的结构化摘要。
     * @return 已归一化的事件记录。
     */
    public AgentRunEventRecord appendEvent(
            String runId,
            String projectId,
            String eventType,
            String eventTitle,
            Map<String, ?> payload
    ) {
        var event = new AgentRunEventRecord(
                UUID.randomUUID().toString(),
                requireText(runId, "运行编号不能为空"),
                requireText(projectId, "项目编号不能为空"),
                requireText(eventType, "事件类型不能为空"),
                requireText(eventTitle, "事件标题不能为空"),
                payloadJson(payload),
                clock.instant()
        );
        repository.ifPresent(agentRuntimeRepository -> agentRuntimeRepository.appendEvent(event));
        return event;
    }

    /**
     * 查询项目最近的 Agent 运行。
     *
     * <p>该查询直接委托正式仓储；没有仓储时返回空列表。limit 小于 1 时返回空列表，
     * 保持和持久化接口一致的边界行为。</p>
     */
    public List<AgentRunRecord> recentRuns(String projectId, int limit) {
        if (limit < 1) {
            return List.of();
        }
        requireText(projectId, "项目编号不能为空");
        return repository.map(agentRuntimeRepository -> agentRuntimeRepository.recentRuns(projectId, limit))
                .orElseGet(List::of);
    }

    /**
     * 查询单次 Agent 运行事件时间线。
     *
     * <p>返回顺序由仓储保证为发生时间正序；无仓储时返回空列表，用于默认文件模式和前端空态。</p>
     */
    public List<AgentRunEventRecord> eventsForRun(String runId) {
        requireText(runId, "运行编号不能为空");
        return repository.map(agentRuntimeRepository -> agentRuntimeRepository.eventsForRun(runId))
                .orElseGet(List::of);
    }

    /**
     * 按 ID 查询单次 Agent 运行主记录。
     *
     * <p>该只读入口面向 Worker 执行计划和后续用户级审计使用。方法只返回主记录，
     * 不加载事件 payload，避免在执行前扩大 prompt、工具输出或其他敏感摘要的读取面。</p>
     *
     * @param runId 运行 ID。
     * @return 存在时返回运行主记录，否则返回空。
     */
    public Optional<AgentRunRecord> findRun(String runId) {
        return findSourceRun(requireText(runId, "运行编号不能为空"));
    }

    private Optional<AgentRunRecord> findSourceRun(String runId) {
        return repository.flatMap(agentRuntimeRepository -> agentRuntimeRepository.findRun(runId));
    }

    private AgentRunRecoveryPlan recoveryPlanFor(AgentRunRecord sourceRun) {
        if (sourceRun.status() != AgentRunStatus.FAILED) {
            return AgentRunRecoveryPlan.blocked(sourceRun.id(), "只有失败运行可以恢复", sourceRun);
        }
        if (!sourceRun.retryable()) {
            return AgentRunRecoveryPlan.blocked(sourceRun.id(), "该失败运行标记为不可重试", sourceRun);
        }
        return AgentRunRecoveryPlan.canRetry(sourceRun, "创建新的排队运行，保留原运行作为恢复来源");
    }

    private String payloadJson(Map<String, ?> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Agent 运行事件 payload 无法序列化", exception);
        }
    }

    private Instant normalizeStartedAt(AgentRunStatus status, Instant startedAt, Instant now) {
        if (startedAt != null) {
            return startedAt;
        }
        return status == AgentRunStatus.RUNNING || isTerminal(status) ? now : null;
    }

    private Instant normalizeFinishedAt(AgentRunStatus status, Instant finishedAt, Instant now) {
        if (finishedAt != null) {
            return finishedAt;
        }
        return isTerminal(status) ? now : null;
    }

    private boolean isTerminal(AgentRunStatus status) {
        return status == AgentRunStatus.SUCCEEDED
                || status == AgentRunStatus.FAILED
                || status == AgentRunStatus.CANCELED;
    }

    private String actorOrSystem(String value) {
        if (value == null || value.isBlank()) {
            return SYSTEM_ACTOR;
        }
        return value.trim();
    }

    private String textOrGenerated(String value) {
        if (value == null || value.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return value.trim();
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String failureSummaryOrDefault(String value) {
        var normalized = trimToEmpty(value).replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            return "未提供失败摘要";
        }
        return normalized.length() <= 800 ? normalized : normalized.substring(0, 800);
    }

    private String toolSummaryOrDefault(String value) {
        var normalized = trimToEmpty(value).replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            return "未提供工具摘要";
        }
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    private String cacheSourceOrUnknown(String value) {
        var normalized = trimToEmpty(value).toUpperCase();
        return normalized.isBlank() ? "UNKNOWN" : normalized;
    }

    private ModelRole parseModelRole(String value) {
        try {
            return ModelRole.valueOf(requireText(value, "角色不能为空").toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("运行角色无法恢复：" + value, exception);
        }
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
