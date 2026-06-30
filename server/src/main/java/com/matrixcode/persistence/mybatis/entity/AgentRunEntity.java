package com.matrixcode.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.matrixcode.agentruntime.domain.AgentRunRecord;
import com.matrixcode.agentruntime.domain.AgentRunStatus;

import java.time.Instant;

@TableName("matrixcode_agent_runs")
public class AgentRunEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;
    private String projectId;
    private String roleKey;
    private String agentKind;
    private String actorUserId;
    private String providerId;
    private String modelName;
    private String status;
    private String goal;
    @TableField(value = "summary", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String summary;
    @TableField(value = "failure_summary", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String failureSummary;
    private Boolean retryable;
    @TableField(value = "retry_of_run_id", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String retryOfRunId;
    private Instant createdAt;
    @TableField(value = "started_at", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private Instant startedAt;
    @TableField(value = "finished_at", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private Instant finishedAt;
    private Instant updatedAt;
    @TableField(value = "claimed_by_user_id", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String claimedByUserId;
    @TableField(value = "claimed_at", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private Instant claimedAt;
    @TableField(value = "claim_expires_at", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private Instant claimExpiresAt;

    /**
     * 将领域层运行记录转换为 MyBatis-Plus 可写入的实体。
     *
     * <p>实体只承担数据库列映射职责，业务状态仍以 `AgentRunRecord` 为准，避免 ORM 注解污染领域对象。</p>
     */
    public static AgentRunEntity fromDomain(AgentRunRecord record) {
        var entity = new AgentRunEntity();
        entity.setId(record.id());
        entity.setProjectId(record.projectId());
        entity.setRoleKey(record.roleKey());
        entity.setAgentKind(record.agentKind());
        entity.setActorUserId(record.actorUserId());
        entity.setProviderId(record.providerId());
        entity.setModelName(record.modelName());
        entity.setStatus(record.status().name());
        entity.setGoal(record.goal());
        entity.setSummary(record.summary());
        entity.setFailureSummary(record.failureSummary());
        entity.setRetryable(record.retryable());
        entity.setRetryOfRunId(record.retryOfRunId());
        entity.setCreatedAt(record.createdAt());
        entity.setStartedAt(record.startedAt());
        entity.setFinishedAt(record.finishedAt());
        entity.setUpdatedAt(record.updatedAt());
        entity.setClaimedByUserId(record.claimedByUserId());
        entity.setClaimedAt(record.claimedAt());
        entity.setClaimExpiresAt(record.claimExpiresAt());
        return entity;
    }

    /**
     * 将数据库实体恢复为领域记录。
     *
     * <p>该方法集中处理状态枚举转换，仓储查询时不用在多个位置重复映射逻辑。</p>
     */
    public AgentRunRecord toDomain() {
        return new AgentRunRecord(
                id,
                projectId,
                roleKey,
                agentKind,
                actorUserId,
                providerId,
                modelName,
                AgentRunStatus.valueOf(status),
                goal,
                summary,
                failureSummary,
                Boolean.TRUE.equals(retryable),
                retryOfRunId,
                createdAt,
                startedAt,
                finishedAt,
                updatedAt,
                claimedByUserId,
                claimedAt,
                claimExpiresAt
        );
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getRoleKey() {
        return roleKey;
    }

    public void setRoleKey(String roleKey) {
        this.roleKey = roleKey;
    }

    public String getAgentKind() {
        return agentKind;
    }

    public void setAgentKind(String agentKind) {
        this.agentKind = agentKind;
    }

    public String getActorUserId() {
        return actorUserId;
    }

    public void setActorUserId(String actorUserId) {
        this.actorUserId = actorUserId;
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getFailureSummary() {
        return failureSummary;
    }

    public void setFailureSummary(String failureSummary) {
        this.failureSummary = failureSummary;
    }

    public Boolean getRetryable() {
        return retryable;
    }

    public void setRetryable(Boolean retryable) {
        this.retryable = retryable;
    }

    public String getRetryOfRunId() {
        return retryOfRunId;
    }

    public void setRetryOfRunId(String retryOfRunId) {
        this.retryOfRunId = retryOfRunId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getClaimedByUserId() {
        return claimedByUserId;
    }

    public void setClaimedByUserId(String claimedByUserId) {
        this.claimedByUserId = claimedByUserId;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(Instant claimedAt) {
        this.claimedAt = claimedAt;
    }

    public Instant getClaimExpiresAt() {
        return claimExpiresAt;
    }

    public void setClaimExpiresAt(Instant claimExpiresAt) {
        this.claimExpiresAt = claimExpiresAt;
    }
}
