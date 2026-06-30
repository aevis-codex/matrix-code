package com.matrixcode.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.matrixcode.agentruntime.domain.AgentRunEventRecord;

import java.time.Instant;

@TableName("matrixcode_agent_run_events")
public class AgentRunEventEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    private String runId;
    private String projectId;
    private String eventType;
    private String eventTitle;
    @TableField(value = "event_payload", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String eventPayload;
    private Instant occurredAt;

    /**
     * 将领域事件转换为 MyBatis-Plus 实体。
     *
     * <p>事件表是追加型审计时间线，因此实体保留完整 payload 和发生时间，不在转换层做裁剪。</p>
     */
    public static AgentRunEventEntity fromDomain(AgentRunEventRecord record) {
        var entity = new AgentRunEventEntity();
        entity.setId(record.id());
        entity.setRunId(record.runId());
        entity.setProjectId(record.projectId());
        entity.setEventType(record.eventType());
        entity.setEventTitle(record.eventTitle());
        entity.setEventPayload(record.eventPayload());
        entity.setOccurredAt(record.occurredAt());
        return entity;
    }

    /**
     * 将数据库实体恢复为领域事件。
     *
     * <p>仓储读取事件时间线时统一调用该方法，保证排序逻辑和字段映射分离。</p>
     */
    public AgentRunEventRecord toDomain() {
        return new AgentRunEventRecord(
                id,
                runId,
                projectId,
                eventType,
                eventTitle,
                eventPayload,
                occurredAt
        );
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getEventTitle() {
        return eventTitle;
    }

    public void setEventTitle(String eventTitle) {
        this.eventTitle = eventTitle;
    }

    public String getEventPayload() {
        return eventPayload;
    }

    public void setEventPayload(String eventPayload) {
        this.eventPayload = eventPayload;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }
}
