package com.matrixcode.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.matrixcode.document.domain.DocumentState;
import com.matrixcode.document.domain.DocumentType;
import com.matrixcode.document.domain.DocumentVersion;

import java.time.Instant;

@TableName("matrixcode_documents")
public class DocumentEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    private String projectId;
    private String documentType;
    private String title;
    private String status;
    private Integer version;
    private Boolean frozen;
    @TableField(value = "content", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String content;
    @TableField(value = "created_by_role", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.NEVER)
    private String createdByRole;
    @TableField(value = "updated_by_role", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String updatedByRole;
    @TableField(value = "created_at", updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;
    private Instant updatedAt;
    @TableField(value = "parent_version_id", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String parentVersionId;
    @TableField(value = "frozen_by", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String frozenBy;
    @TableField(value = "frozen_at", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private Instant frozenAt;

    /**
     * 将文档版本领域对象转换为正式表实体。
     *
     * <p>文档表中 `frozen` 是状态冗余字段，由领域状态统一推导；`updated_at` 保持旧 JDBC
     * 仓储行为，冻结版本使用冻结时间，普通草稿使用创建时间。</p>
     */
    public static DocumentEntity fromDomain(DocumentVersion document) {
        var entity = new DocumentEntity();
        entity.setId(document.id());
        entity.setProjectId(document.projectId());
        entity.setDocumentType(document.type().name());
        entity.setTitle(document.title());
        entity.setStatus(document.state().name());
        entity.setVersion(document.version());
        entity.setFrozen(document.state() == DocumentState.FROZEN);
        entity.setContent(document.content());
        entity.setCreatedByRole(null);
        entity.setUpdatedByRole(blankToNull(document.frozenBy()));
        entity.setCreatedAt(timestampOrEpoch(document.createdAt()));
        entity.setUpdatedAt(updatedAt(document));
        entity.setParentVersionId(blankToNull(document.parentVersionId()));
        entity.setFrozenBy(blankToNull(document.frozenBy()));
        entity.setFrozenAt(document.frozenAt());
        return entity;
    }

    /**
     * 将正式表实体恢复为文档版本领域对象。
     *
     * <p>历史数据可能缺少扩展字段；转换时集中兜底版本、时间和正文，避免业务服务直接感知
     * 数据库空值细节。</p>
     */
    public DocumentVersion toDomain() {
        return new DocumentVersion(
                id,
                projectId,
                DocumentType.valueOf(documentType),
                title,
                content == null ? "" : content,
                version == null ? 1 : version,
                DocumentState.valueOf(status),
                parentVersionId,
                createdAt == null ? Instant.EPOCH : createdAt,
                frozenBy,
                frozenAt
        );
    }

    private static Instant updatedAt(DocumentVersion document) {
        if (document.frozenAt() != null) {
            return document.frozenAt();
        }
        return timestampOrEpoch(document.createdAt());
    }

    private static Instant timestampOrEpoch(Instant instant) {
        return instant == null ? Instant.EPOCH : instant;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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

    public String getDocumentType() {
        return documentType;
    }

    public void setDocumentType(String documentType) {
        this.documentType = documentType;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Boolean getFrozen() {
        return frozen;
    }

    public void setFrozen(Boolean frozen) {
        this.frozen = frozen;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getCreatedByRole() {
        return createdByRole;
    }

    public void setCreatedByRole(String createdByRole) {
        this.createdByRole = createdByRole;
    }

    public String getUpdatedByRole() {
        return updatedByRole;
    }

    public void setUpdatedByRole(String updatedByRole) {
        this.updatedByRole = updatedByRole;
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

    public String getParentVersionId() {
        return parentVersionId;
    }

    public void setParentVersionId(String parentVersionId) {
        this.parentVersionId = parentVersionId;
    }

    public String getFrozenBy() {
        return frozenBy;
    }

    public void setFrozenBy(String frozenBy) {
        this.frozenBy = frozenBy;
    }

    public Instant getFrozenAt() {
        return frozenAt;
    }

    public void setFrozenAt(Instant frozenAt) {
        this.frozenAt = frozenAt;
    }
}
