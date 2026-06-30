package com.matrixcode.identity.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 真实库首个项目初始化配置。
 *
 * <p>该配置默认关闭，只用于空库或新环境首次创建项目 Owner。配置类只负责绑定和清洗
 * 环境变量，不直接写入数据库；是否覆盖已有成员由服务层统一判断。</p>
 */
@Component
@ConfigurationProperties(prefix = "matrixcode.bootstrap.initial-project")
public class ProjectBootstrapProperties {

    private boolean enabled = false;
    private String projectId = "";
    private String projectName = "";
    private String ownerUserId = "";
    private String ownerDisplayName = "";
    private String currentStage = "真实库初始化";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = trim(projectId);
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = trim(projectName);
    }

    public String getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(String ownerUserId) {
        this.ownerUserId = trim(ownerUserId);
    }

    public String getOwnerDisplayName() {
        return ownerDisplayName;
    }

    public void setOwnerDisplayName(String ownerDisplayName) {
        this.ownerDisplayName = trim(ownerDisplayName);
    }

    public String getCurrentStage() {
        return currentStage;
    }

    public void setCurrentStage(String currentStage) {
        this.currentStage = currentStage == null || currentStage.isBlank() ? "真实库初始化" : currentStage.trim();
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
