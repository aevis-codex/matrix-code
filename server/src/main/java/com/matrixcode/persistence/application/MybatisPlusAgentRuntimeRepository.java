package com.matrixcode.persistence.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.matrixcode.agentruntime.application.AgentRuntimeRepository;
import com.matrixcode.agentruntime.domain.AgentRunEventRecord;
import com.matrixcode.agentruntime.domain.AgentRunRecord;
import com.matrixcode.agentruntime.domain.AgentRunStatus;
import com.matrixcode.persistence.mybatis.entity.AgentRunEntity;
import com.matrixcode.persistence.mybatis.entity.AgentRunEventEntity;
import com.matrixcode.persistence.mybatis.entity.MatrixProjectEntity;
import com.matrixcode.persistence.mybatis.entity.MatrixUserEntity;
import com.matrixcode.persistence.mybatis.mapper.AgentRunEventMapper;
import com.matrixcode.persistence.mybatis.mapper.AgentRunMapper;
import com.matrixcode.persistence.mybatis.mapper.MatrixProjectMapper;
import com.matrixcode.persistence.mybatis.mapper.MatrixUserMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "matrixcode.persistence", name = "mode", havingValue = "jdbc")
public class MybatisPlusAgentRuntimeRepository implements AgentRuntimeRepository {

    private final AgentRunMapper runMapper;
    private final AgentRunEventMapper eventMapper;
    private final MatrixUserMapper userMapper;
    private final MatrixProjectMapper projectMapper;

    public MybatisPlusAgentRuntimeRepository(
            AgentRunMapper runMapper,
            AgentRunEventMapper eventMapper,
            MatrixUserMapper userMapper,
            MatrixProjectMapper projectMapper
    ) {
        this.runMapper = runMapper;
        this.eventMapper = eventMapper;
        this.userMapper = userMapper;
        this.projectMapper = projectMapper;
    }

    /**
     * 保存一次 Agent 运行的主状态。
     *
     * <p>方法在同一事务内补齐操作者用户和项目外键，再执行运行主记录的 upsert。
     * 已存在的运行只覆盖主状态，不会删除 `matrixcode_agent_run_events` 中的事件审计时间线。</p>
     */
    @Override
    @Transactional
    public void saveRun(AgentRunRecord run) {
        var now = Instant.now();
        ensureUser(run.actorUserId(), now);
        if (run.claimedByUserId() != null) {
            ensureUser(run.claimedByUserId(), now);
        }
        ensureProject(run.projectId(), now);
        var entity = AgentRunEntity.fromDomain(run);
        if (runMapper.updateById(entity) == 0) {
            runMapper.insert(entity);
        }
    }

    /**
     * 追加一次 Agent 运行事件。
     *
     * <p>事件表按主键保持不可覆盖语义：重复事件 ID 会由数据库约束拒绝，调用方据此发现重复写入。</p>
     */
    @Override
    public void appendEvent(AgentRunEventRecord event) {
        eventMapper.insert(AgentRunEventEntity.fromDomain(event));
    }

    /**
     * 按主键读取单次 Agent 运行。
     *
     * <p>恢复重试入口使用该方法校验来源运行是否存在、是否属于当前项目以及是否允许重试。
     * 方法只读取 `matrixcode_agent_runs` 主表，不读取事件 payload，避免把 prompt、工具输出等审计明细带入恢复决策。</p>
     */
    @Override
    public Optional<AgentRunRecord> findRun(String runId) {
        return Optional.ofNullable(runMapper.selectById(runId))
                .map(AgentRunEntity::toDomain);
    }

    /**
     * 从项目队列中认领下一条排队 Agent 运行。
     *
     * <p>方法先读取最早的 `QUEUED` 候选，再通过条件更新把它推进到 `RUNNING`。条件中同时包含
     * 运行 ID、项目 ID 和旧状态，确保并发 Worker 只有一个能成功更新同一条记录。该方法只更新主记录
     * 和租约字段，不追加事件；事件由 `AgentRuntimeService` 统一写入。</p>
     */
    @Override
    @Transactional
    public Optional<AgentRunRecord> claimNextQueuedRun(
            String projectId,
            String claimedByUserId,
            Instant claimedAt,
            Instant claimExpiresAt
    ) {
        var now = claimedAt == null ? Instant.now() : claimedAt;
        ensureUser(claimedByUserId, now);
        ensureProject(projectId, now);
        var candidate = runMapper.selectList(new LambdaQueryWrapper<AgentRunEntity>()
                        .eq(AgentRunEntity::getProjectId, projectId)
                        .eq(AgentRunEntity::getStatus, AgentRunStatus.QUEUED.name())
                        .orderByAsc(AgentRunEntity::getCreatedAt)
                        .orderByAsc(AgentRunEntity::getId)
                        .last("limit 1"))
                .stream()
                .findFirst();
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        var updatedRows = runMapper.update(null, new LambdaUpdateWrapper<AgentRunEntity>()
                .set(AgentRunEntity::getStatus, AgentRunStatus.RUNNING.name())
                .set(AgentRunEntity::getSummary, "运行已认领")
                .set(AgentRunEntity::getStartedAt, now)
                .set(AgentRunEntity::getFinishedAt, null)
                .set(AgentRunEntity::getUpdatedAt, now)
                .set(AgentRunEntity::getClaimedByUserId, claimedByUserId)
                .set(AgentRunEntity::getClaimedAt, now)
                .set(AgentRunEntity::getClaimExpiresAt, claimExpiresAt)
                .eq(AgentRunEntity::getId, candidate.get().getId())
                .eq(AgentRunEntity::getProjectId, projectId)
                .eq(AgentRunEntity::getStatus, AgentRunStatus.QUEUED.name()));
        if (updatedRows == 0) {
            return Optional.empty();
        }
        return findRun(candidate.get().getId());
    }

    /**
     * 续期当前认领人的 Agent 运行租约。
     *
     * <p>更新条件显式包含项目、运行 ID、`RUNNING` 状态和认领人，保证其他 Worker 不能刷新本运行租约。
     * 方法只更新租约到期时间和更新时间，不改写目标、模型、失败摘要或事件时间线。</p>
     */
    @Override
    @Transactional
    public Optional<AgentRunRecord> renewClaimLease(
            String projectId,
            String runId,
            String claimedByUserId,
            Instant renewedAt,
            Instant claimExpiresAt
    ) {
        var now = renewedAt == null ? Instant.now() : renewedAt;
        ensureUser(claimedByUserId, now);
        var updatedRows = runMapper.update(null, new LambdaUpdateWrapper<AgentRunEntity>()
                .set(AgentRunEntity::getUpdatedAt, now)
                .set(AgentRunEntity::getClaimExpiresAt, claimExpiresAt)
                .eq(AgentRunEntity::getId, runId)
                .eq(AgentRunEntity::getProjectId, projectId)
                .eq(AgentRunEntity::getStatus, AgentRunStatus.RUNNING.name())
                .eq(AgentRunEntity::getClaimedByUserId, claimedByUserId));
        if (updatedRows == 0) {
            return Optional.empty();
        }
        return findRun(runId);
    }

    /**
     * 回收当前项目中过期的运行租约。
     *
     * <p>方法先读取有限数量的候选，再逐条用条件更新回收。更新条件再次校验 `RUNNING` 和
     * `claim_expires_at <= now`，防止读取候选后另一个 Worker 已经成功续租仍被误标记为失败。</p>
     */
    @Override
    @Transactional
    public List<AgentRunRecord> expireRunningLeases(
            String projectId,
            Instant now,
            int limit,
            String failureSummary
    ) {
        if (limit < 1) {
            return List.of();
        }
        var expiredAt = now == null ? Instant.now() : now;
        var safeFailureSummary = failureSummary == null || failureSummary.isBlank()
                ? "Worker 租约已过期，运行被系统回收"
                : failureSummary.trim();
        var candidates = runMapper.selectList(new LambdaQueryWrapper<AgentRunEntity>()
                .eq(AgentRunEntity::getProjectId, projectId)
                .eq(AgentRunEntity::getStatus, AgentRunStatus.RUNNING.name())
                .isNotNull(AgentRunEntity::getClaimExpiresAt)
                .le(AgentRunEntity::getClaimExpiresAt, expiredAt)
                .orderByAsc(AgentRunEntity::getClaimExpiresAt)
                .orderByAsc(AgentRunEntity::getId)
                .last("limit " + limit));
        var expiredRuns = new ArrayList<AgentRunRecord>();
        for (var candidate : candidates) {
            var updatedRows = runMapper.update(null, new LambdaUpdateWrapper<AgentRunEntity>()
                    .set(AgentRunEntity::getStatus, AgentRunStatus.FAILED.name())
                    .set(AgentRunEntity::getSummary, safeFailureSummary)
                    .set(AgentRunEntity::getFailureSummary, safeFailureSummary)
                    .set(AgentRunEntity::getRetryable, true)
                    .set(AgentRunEntity::getFinishedAt, expiredAt)
                    .set(AgentRunEntity::getUpdatedAt, expiredAt)
                    .eq(AgentRunEntity::getId, candidate.getId())
                    .eq(AgentRunEntity::getProjectId, projectId)
                    .eq(AgentRunEntity::getStatus, AgentRunStatus.RUNNING.name())
                    .le(AgentRunEntity::getClaimExpiresAt, expiredAt));
            if (updatedRows > 0) {
                findRun(candidate.getId()).ifPresent(expiredRuns::add);
            }
        }
        return expiredRuns;
    }

    /**
     * 按项目读取最近的 Agent 运行主记录。
     *
     * <p>工作台运行中心使用该方法展示最近执行情况。limit 小于 1 时直接返回空列表，避免拼接无效 SQL。</p>
     */
    @Override
    public List<AgentRunRecord> recentRuns(String projectId, int limit) {
        if (limit < 1) {
            return List.of();
        }
        return runMapper.selectList(new LambdaQueryWrapper<AgentRunEntity>()
                        .eq(AgentRunEntity::getProjectId, projectId)
                        .orderByDesc(AgentRunEntity::getCreatedAt)
                        .orderByDesc(AgentRunEntity::getId)
                        .last("limit " + limit))
                .stream()
                .map(AgentRunEntity::toDomain)
                .toList();
    }

    /**
     * 读取单次 Agent 运行的事件时间线。
     *
     * <p>事件按发生时间和事件 ID 正序返回，供运行详情、审计复盘和失败回溯直接使用。</p>
     */
    @Override
    public List<AgentRunEventRecord> eventsForRun(String runId) {
        return eventMapper.selectList(new LambdaQueryWrapper<AgentRunEventEntity>()
                        .eq(AgentRunEventEntity::getRunId, runId)
                        .orderByAsc(AgentRunEventEntity::getOccurredAt)
                        .orderByAsc(AgentRunEventEntity::getId))
                .stream()
                .map(AgentRunEventEntity::toDomain)
                .toList();
    }

    private void ensureUser(String userId, Instant now) {
        if (userMapper.updateById(MatrixUserEntity.touch(userId, now)) == 0) {
            userMapper.insert(MatrixUserEntity.fallbackUser(userId, now));
        }
    }

    private void ensureProject(String projectId, Instant now) {
        if (projectMapper.updateById(MatrixProjectEntity.touch(projectId, now)) == 0) {
            projectMapper.insert(MatrixProjectEntity.fallbackProject(projectId, now));
        }
    }
}
