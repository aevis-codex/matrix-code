package com.matrixcode.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("matrixcode_users")
public class MatrixUserEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    private String username;
    private String displayName;
    @TableField(value = "email", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String email;
    private String status;
    private String passwordHash;
    private Boolean superAdmin;
    @TableField(value = "password_updated_at", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private Instant passwordUpdatedAt;
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * 构造 Agent 运行仓储所需的兜底用户。
     *
     * <p>该用户只用于满足运行记录的操作者外键，真实用户资料后续仍由身份模块覆盖和维护。</p>
     */
    public static MatrixUserEntity fallbackUser(String userId, Instant now) {
        var entity = new MatrixUserEntity();
        entity.setId(userId);
        entity.setUsername(userId);
        entity.setDisplayName(userId);
        entity.setEmail("");
        entity.setStatus("ACTIVE");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    /**
     * 构造只更新 `updated_at` 的用户实体。
     *
     * <p>用于确认操作者存在且刷新活跃时间，不覆盖真实用户名、展示名或邮箱。</p>
     */
    public static MatrixUserEntity touch(String userId, Instant now) {
        var entity = new MatrixUserEntity();
        entity.setId(userId);
        entity.setUpdatedAt(now);
        return entity;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Boolean getSuperAdmin() {
        return superAdmin;
    }

    public void setSuperAdmin(Boolean superAdmin) {
        this.superAdmin = superAdmin;
    }

    public Instant getPasswordUpdatedAt() {
        return passwordUpdatedAt;
    }

    public void setPasswordUpdatedAt(Instant passwordUpdatedAt) {
        this.passwordUpdatedAt = passwordUpdatedAt;
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
