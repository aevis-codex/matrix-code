package com.matrixcode.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("matrixcode_projects")
public class MatrixProjectEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;
    private String name;
    @TableField(value = "description", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String description;
    @TableField(value = "owner_user_id", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String ownerUserId;
    private String status;
    private String currentStage;
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * 构造 Agent 运行仓储所需的兜底项目。
     *
     * <p>当上层先写运行记录而项目尚未初始化时，仓储用该实体补齐外键依赖，
     * 避免运行审计链路因缺少项目行而中断。</p>
     */
    public static MatrixProjectEntity fallbackProject(String projectId, Instant now) {
        var entity = new MatrixProjectEntity();
        entity.setId(projectId);
        entity.setName(projectId);
        entity.setDescription("");
        entity.setOwnerUserId(null);
        entity.setStatus("ACTIVE");
        entity.setCurrentStage("Agent 运行");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    /**
     * 构造只更新 `updated_at` 的项目实体。
     *
     * <p>MyBatis-Plus 默认只更新非空字段，因此该实体用于刷新现有项目时间戳，
     * 不会覆盖项目名称、描述等业务字段。</p>
     */
    public static MatrixProjectEntity touch(String projectId, Instant now) {
        var entity = new MatrixProjectEntity();
        entity.setId(projectId);
        entity.setUpdatedAt(now);
        return entity;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(String ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCurrentStage() {
        return currentStage;
    }

    public void setCurrentStage(String currentStage) {
        this.currentStage = currentStage;
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
