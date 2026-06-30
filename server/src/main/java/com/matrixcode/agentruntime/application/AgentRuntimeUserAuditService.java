package com.matrixcode.agentruntime.application;

import com.matrixcode.agentruntime.domain.AgentRunEventRecord;
import com.matrixcode.agentruntime.domain.AgentRunRecord;
import com.matrixcode.agentruntime.domain.AgentRuntimeUserAuditEntry;
import com.matrixcode.agentruntime.domain.AgentRuntimeUserAuditReport;
import com.matrixcode.identity.application.ProjectIdentityService;
import com.matrixcode.identity.domain.ProjectMember;
import com.matrixcode.modelgateway.application.ModelGatewayService;
import com.matrixcode.modelgateway.domain.ModelRequestRecord;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class AgentRuntimeUserAuditService {

    private static final int DEFAULT_LIMIT = 50;

    private final AgentRuntimeService agentRuntimeService;
    private final ModelGatewayService modelGatewayService;
    private final ProjectIdentityService identityService;

    public AgentRuntimeUserAuditService(
            AgentRuntimeService agentRuntimeService,
            ModelGatewayService modelGatewayService,
            ProjectIdentityService identityService
    ) {
        this.agentRuntimeService = Objects.requireNonNull(agentRuntimeService, "agentRuntimeService 不能为空");
        this.modelGatewayService = Objects.requireNonNull(modelGatewayService, "modelGatewayService 不能为空");
        this.identityService = Objects.requireNonNull(identityService, "identityService 不能为空");
    }

    /**
     * 查询单个用户在项目内的 Agent Runtime 责任审计报告。
     *
     * <p>该方法只读取已经脱敏的 Agent 运行、事件和模型请求记录，不写数据库、不触发模型、
     * 不执行命令、不读取文件、不应用 Patch。责任人计算规则为：当前 Worker 租约认领人优先；
     * 未认领时按项目成员角色匹配；仍未匹配时回退到运行操作者。</p>
     *
     * @param projectId 项目 ID。
     * @param userId 用户或 Worker ID。
     * @param limit 最近运行读取上限；小于 1 时使用默认值。
     * @return 用户责任审计报告。
     */
    public AgentRuntimeUserAuditReport audit(String projectId, String userId, int limit) {
        var normalizedProjectId = requireText(projectId, "项目编号不能为空");
        var normalizedUserId = requireText(userId, "用户编号不能为空");
        var normalizedLimit = limit < 1 ? DEFAULT_LIMIT : Math.min(limit, 100);
        var members = identityService.members(normalizedProjectId);
        var modelRequests = modelGatewayService.recentRequests(normalizedProjectId);
        var entries = agentRuntimeService.recentRuns(normalizedProjectId, normalizedLimit).stream()
                .map(run -> entryIfRelated(normalizedProjectId, normalizedUserId, run, members, modelRequests))
                .flatMap(Optional::stream)
                .toList();
        return AgentRuntimeUserAuditReport.from(normalizedProjectId, normalizedUserId, entries);
    }

    private Optional<AgentRuntimeUserAuditEntry> entryIfRelated(
            String projectId,
            String userId,
            AgentRunRecord run,
            List<ProjectMember> members,
            List<ModelRequestRecord> projectModelRequests
    ) {
        var runModelRequests = projectModelRequests.stream()
                .filter(request -> run.id().equals(request.agentRunId()))
                .toList();
        var entry = entryFor(projectId, userId, run, members, runModelRequests);
        return relatedToUser(entry, runModelRequests, userId) ? Optional.of(entry) : Optional.empty();
    }

    private AgentRuntimeUserAuditEntry entryFor(
            String projectId,
            String userId,
            AgentRunRecord run,
            List<ProjectMember> members,
            List<ModelRequestRecord> runModelRequests
    ) {
        var responsibility = responsibilityFor(run, members);
        var events = agentRuntimeService.eventsForRun(run.id());
        var lastEvent = events.stream()
                .max(Comparator.comparing(AgentRunEventRecord::occurredAt)
                        .thenComparing(AgentRunEventRecord::id))
                .orElse(null);
        var lastModelRequest = runModelRequests.stream()
                .max(Comparator.comparing(ModelRequestRecord::createdAt)
                        .thenComparing(ModelRequestRecord::requestId))
                .orElse(null);
        return new AgentRuntimeUserAuditEntry(
                projectId,
                run.id(),
                userId,
                responsibility.userId(),
                responsibility.source(),
                run.roleKey(),
                run.agentKind(),
                run.status().name(),
                run.actorUserId(),
                run.claimedByUserId(),
                run.goal(),
                run.summary(),
                run.failureSummary(),
                events.size(),
                Math.toIntExact(events.stream().filter(event -> "TOOL_TRACE".equals(event.eventType())).count()),
                runModelRequests.size(),
                lastEvent == null ? "" : lastEvent.eventType(),
                lastEvent == null ? "" : lastEvent.eventTitle(),
                lastModelRequest == null ? "" : lastModelRequest.requestId(),
                run.updatedAt()
        );
    }

    private Responsibility responsibilityFor(AgentRunRecord run, List<ProjectMember> members) {
        if (run.claimedByUserId() != null && !run.claimedByUserId().isBlank()) {
            return new Responsibility(run.claimedByUserId(), "CLAIMED_WORKER");
        }
        return members.stream()
                .filter(member -> member.roleKey().equalsIgnoreCase(run.roleKey()))
                .findFirst()
                .map(member -> new Responsibility(member.userId(), "ROLE_MEMBER"))
                .orElseGet(() -> new Responsibility(run.actorUserId(), "RUN_ACTOR"));
    }

    private boolean relatedToUser(
            AgentRuntimeUserAuditEntry entry,
            List<ModelRequestRecord> runModelRequests,
            String userId
    ) {
        return entry.responsibleUserId().equals(userId)
                || entry.actorUserId().equals(userId)
                || entry.claimedByUserId().equals(userId)
                || runModelRequests.stream().anyMatch(request -> request.actorUserId().equals(userId));
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private record Responsibility(String userId, String source) {
        private Responsibility {
            userId = userId == null || userId.isBlank() ? "system" : userId.trim();
            source = source == null || source.isBlank() ? "RUN_ACTOR" : source.trim();
        }
    }
}
