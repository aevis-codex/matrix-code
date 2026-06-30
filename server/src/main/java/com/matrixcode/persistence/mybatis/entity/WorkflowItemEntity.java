package com.matrixcode.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.matrixcode.workflow.domain.WorkflowItem;
import com.matrixcode.workflow.domain.WorkflowState;

import java.time.Instant;

@TableName("matrixcode_workflow_items")
public class WorkflowItemEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;
    private String projectId;
    private String title;
    private String state;
    @TableField(value = "created_at", updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * 将工作流项转换为正式表实体。
     *
     * <p>领域对象不携带创建和更新时间，因此仓储在保存时传入当前时间；更新时
     * `created_at` 不参与覆盖，保持首次插入时间稳定。</p>
     */
    public static WorkflowItemEntity fromDomain(WorkflowItem item, Instant now) {
        var entity = new WorkflowItemEntity();
        entity.setId(item.id());
        entity.setProjectId(item.projectId());
        entity.setTitle(item.title());
        entity.setState(item.state().name());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    public WorkflowItem toDomain() {
        return new WorkflowItem(id, projectId, title, WorkflowState.valueOf(state));
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

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
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
}
