package com.matrixcode.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.matrixcode.bug.domain.BugSeverity;
import com.matrixcode.bug.domain.BugStatus;
import com.matrixcode.bug.domain.ProjectBug;

import java.time.Instant;

@TableName("matrixcode_bugs")
public class BugEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;
    private String projectId;
    private String title;
    @TableField(value = "description", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String description;
    private String severity;
    private String status;
    private String createdByRole;
    private String currentOwnerRole;
    @TableField(value = "related_document_id", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String relatedDocumentId;
    @TableField(value = "created_at", updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;
    private Instant updatedAt;
    @TableField(value = "reproduction_steps", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String reproductionSteps;
    @TableField(value = "expected_result", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String expectedResult;
    @TableField(value = "actual_result", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String actualResult;
    @TableField(value = "last_note", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String lastNote;

    /**
     * 将 Bug 领域对象转换为正式表实体。
     *
     * <p>`description` 是第 18 阶段基础字段，继续同步复现步骤以兼容历史查询；工作流字段以
     * 第 29 阶段扩展列为准，允许置空并在更新时覆盖旧值。</p>
     */
    public static BugEntity fromDomain(ProjectBug bug) {
        var entity = new BugEntity();
        entity.setId(bug.id());
        entity.setProjectId(bug.projectId());
        entity.setTitle(bug.title());
        entity.setDescription(bug.steps());
        entity.setSeverity(bug.severity().name());
        entity.setStatus(bug.status().name());
        entity.setCreatedByRole(bug.createdByRole());
        entity.setCurrentOwnerRole(bug.currentOwnerRole());
        entity.setRelatedDocumentId(null);
        entity.setCreatedAt(timestampOrEpoch(bug.updatedAt()));
        entity.setUpdatedAt(timestampOrEpoch(bug.updatedAt()));
        entity.setReproductionSteps(bug.steps());
        entity.setExpectedResult(bug.expected());
        entity.setActualResult(bug.actual());
        entity.setLastNote(bug.lastNote());
        return entity;
    }

    /**
     * 将正式表实体恢复为 Bug 领域对象。
     *
     * <p>历史数据可能只有 `description` 而没有第 29 阶段扩展字段；复现步骤读取时先取
     * `reproduction_steps`，再回退到 `description`，保持旧数据可用。</p>
     */
    public ProjectBug toDomain() {
        return new ProjectBug(
                id,
                projectId,
                title,
                BugSeverity.valueOf(severity),
                BugStatus.valueOf(status),
                textOr(reproductionSteps, description),
                nullToBlank(expectedResult),
                nullToBlank(actualResult),
                createdByRole,
                currentOwnerRole,
                nullToBlank(lastNote),
                updatedAt == null ? Instant.EPOCH : updatedAt
        );
    }

    private static Instant timestampOrEpoch(Instant instant) {
        return instant == null ? Instant.EPOCH : instant;
    }

    private String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? nullToBlank(fallback) : value;
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCreatedByRole() {
        return createdByRole;
    }

    public void setCreatedByRole(String createdByRole) {
        this.createdByRole = createdByRole;
    }

    public String getCurrentOwnerRole() {
        return currentOwnerRole;
    }

    public void setCurrentOwnerRole(String currentOwnerRole) {
        this.currentOwnerRole = currentOwnerRole;
    }

    public String getRelatedDocumentId() {
        return relatedDocumentId;
    }

    public void setRelatedDocumentId(String relatedDocumentId) {
        this.relatedDocumentId = relatedDocumentId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getReproductionSteps() {
        return reproductionSteps;
    }

    public void setReproductionSteps(String reproductionSteps) {
        this.reproductionSteps = reproductionSteps;
    }

    public String getExpectedResult() {
        return expectedResult;
    }

    public void setExpectedResult(String expectedResult) {
        this.expectedResult = expectedResult;
    }

    public String getActualResult() {
        return actualResult;
    }

    public void setActualResult(String actualResult) {
        this.actualResult = actualResult;
    }

    public String getLastNote() {
        return lastNote;
    }

    public void setLastNote(String lastNote) {
        this.lastNote = lastNote;
    }
}
