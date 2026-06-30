package com.matrixcode.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.matrixcode.localexecution.domain.GitDiffSummary;

import java.time.Instant;
import java.util.List;

@TableName("matrixcode_local_git_diff_summaries")
public class LocalGitDiffSummaryEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;
    private String projectId;
    private String workspaceId;
    private Boolean repository;
    private String changedFilesJson;
    private String diffStat;
    private Instant capturedAt;
    private Instant updatedAt;

    /**
     * 将 Git Diff 领域摘要转换为正式表实体。
     *
     * <p>变更文件列表由仓储序列化为 JSON 文本后传入，实体只负责字段映射。</p>
     */
    public static LocalGitDiffSummaryEntity fromDomain(GitDiffSummary summary, String changedFilesJson, Instant now) {
        var entity = new LocalGitDiffSummaryEntity();
        entity.setProjectId(summary.projectId());
        entity.setWorkspaceId(summary.workspaceId());
        entity.setRepository(summary.repository());
        entity.setChangedFilesJson(changedFilesJson);
        entity.setDiffStat(summary.stat());
        entity.setCapturedAt(summary.capturedAt());
        entity.setUpdatedAt(now == null ? Instant.now() : now);
        return entity;
    }

    /**
     * 将正式表实体恢复为 Git Diff 领域摘要。
     */
    public GitDiffSummary toDomain(List<String> changedFiles) {
        return new GitDiffSummary(
                projectId,
                workspaceId,
                repository != null && repository,
                changedFiles,
                diffStat == null ? "" : diffStat,
                capturedAt == null ? Instant.EPOCH : capturedAt
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

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    public Boolean getRepository() {
        return repository;
    }

    public void setRepository(Boolean repository) {
        this.repository = repository;
    }

    public String getChangedFilesJson() {
        return changedFilesJson;
    }

    public void setChangedFilesJson(String changedFilesJson) {
        this.changedFilesJson = changedFilesJson;
    }

    public String getDiffStat() {
        return diffStat;
    }

    public void setDiffStat(String diffStat) {
        this.diffStat = diffStat;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(Instant capturedAt) {
        this.capturedAt = capturedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
