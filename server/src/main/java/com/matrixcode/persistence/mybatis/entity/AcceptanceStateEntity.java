package com.matrixcode.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.matrixcode.workbench.application.WorkbenchStateSnapshot;

import java.time.Instant;

@TableName("matrixcode_acceptance_states")
public class AcceptanceStateEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;
    private String projectId;
    private String documentId;
    private Boolean accepted;
    private String returnToRole;
    private Instant updatedAt;

    /**
     * 构造验收投影实体。
     *
     * <p>验收投影以项目为唯一业务键，保存时覆盖最新文档、是否验收和退回角色，
     * 与旧 JDBC 仓储的 upsert 行为保持一致。</p>
     */
    public static AcceptanceStateEntity fromDomain(
            String projectId,
            WorkbenchStateSnapshot.AcceptanceState acceptance,
            Instant now
    ) {
        var entity = new AcceptanceStateEntity();
        entity.setProjectId(projectId);
        entity.setDocumentId(acceptance.documentId());
        entity.setAccepted(acceptance.accepted());
        entity.setReturnToRole(acceptance.returnToRole());
        entity.setUpdatedAt(now);
        return entity;
    }

    public WorkbenchStateSnapshot.AcceptanceState toDomain() {
        return new WorkbenchStateSnapshot.AcceptanceState(documentId, Boolean.TRUE.equals(accepted), returnToRole);
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

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public Boolean getAccepted() {
        return accepted;
    }

    public void setAccepted(Boolean accepted) {
        this.accepted = accepted;
    }

    public String getReturnToRole() {
        return returnToRole;
    }

    public void setReturnToRole(String returnToRole) {
        this.returnToRole = returnToRole;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
