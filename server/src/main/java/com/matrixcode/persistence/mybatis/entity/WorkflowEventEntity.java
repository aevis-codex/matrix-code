package com.matrixcode.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.matrixcode.workflow.domain.WorkflowEvent;
import com.matrixcode.workflow.domain.WorkflowEventType;
import com.matrixcode.workflow.domain.WorkflowState;

import java.time.Instant;

@TableName("matrixcode_workflow_events")
public class WorkflowEventEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;
    private String projectId;
    private String itemId;
    private String eventType;
    private String fromState;
    private String toState;
    private String actorId;
    private Instant occurredAt;

    /**
     * 构造工作流事件实体。
     *
     * <p>事件表冗余 `project_id` 用于项目级查询和外键约束；该字段由工作流项反查得到，
     * 避免调用方在事件对象中重复传递项目 ID。</p>
     */
    public static WorkflowEventEntity fromDomain(String projectId, WorkflowEvent event) {
        var entity = new WorkflowEventEntity();
        entity.setId(event.id());
        entity.setProjectId(projectId);
        entity.setItemId(event.itemId());
        entity.setEventType(event.type().name());
        entity.setFromState(event.fromState().name());
        entity.setToState(event.toState().name());
        entity.setActorId(event.actorId());
        entity.setOccurredAt(event.occurredAt() == null ? Instant.EPOCH : event.occurredAt());
        return entity;
    }

    public WorkflowEvent toDomain() {
        return new WorkflowEvent(
                id,
                itemId,
                WorkflowEventType.valueOf(eventType),
                WorkflowState.valueOf(fromState),
                WorkflowState.valueOf(toState),
                actorId,
                occurredAt == null ? Instant.EPOCH : occurredAt
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

    public String getItemId() {
        return itemId;
    }

    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getFromState() {
        return fromState;
    }

    public void setFromState(String fromState) {
        this.fromState = fromState;
    }

    public String getToState() {
        return toState;
    }

    public void setToState(String toState) {
        this.toState = toState;
    }

    public String getActorId() {
        return actorId;
    }

    public void setActorId(String actorId) {
        this.actorId = actorId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }
}
