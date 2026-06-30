package com.matrixcode.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.matrixcode.localexecution.domain.LocalTaskLog;
import com.matrixcode.localexecution.domain.LocalTaskLogStream;

import java.time.Instant;

@TableName("matrixcode_local_task_logs")
public class LocalTaskLogEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    private String projectId;
    private String taskId;
    private String stream;
    private String content;
    private Integer sortOrder;
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * 将任务日志转换为正式表实体。
     *
     * <p>`sort_order` 保存内存列表顺序，读取时优先按该字段恢复，保证命令输出在重启后
     * 仍与写入时一致。</p>
     */
    public static LocalTaskLogEntity fromDomain(LocalTaskLog log, int sortOrder, Instant now) {
        var entity = new LocalTaskLogEntity();
        entity.setId(log.id());
        entity.setProjectId(log.projectId());
        entity.setTaskId(log.taskId());
        entity.setStream(log.stream().name());
        entity.setContent(log.content());
        entity.setSortOrder(sortOrder);
        entity.setCreatedAt(log.createdAt());
        entity.setUpdatedAt(now);
        return entity;
    }

    /**
     * 将正式表实体恢复为本地任务日志领域对象。
     */
    public LocalTaskLog toDomain() {
        return new LocalTaskLog(
                id,
                projectId,
                taskId,
                LocalTaskLogStream.valueOf(stream),
                content == null ? "" : content,
                createdAt == null ? Instant.EPOCH : createdAt
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

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getStream() {
        return stream;
    }

    public void setStream(String stream) {
        this.stream = stream;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
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
