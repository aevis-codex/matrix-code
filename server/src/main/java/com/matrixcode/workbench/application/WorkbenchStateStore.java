package com.matrixcode.workbench.application;

import com.matrixcode.bug.domain.ProjectBug;
import com.matrixcode.deployment.domain.ComposeEnvironment;
import com.matrixcode.deployment.domain.ComposeOperationRecord;
import com.matrixcode.deployment.domain.DeploymentHealthCheck;
import com.matrixcode.deployment.domain.DeploymentOperationRecord;
import com.matrixcode.deployment.domain.DeploymentTarget;
import com.matrixcode.document.domain.DocumentVersion;
import com.matrixcode.localexecution.domain.FileOperationRecord;
import com.matrixcode.localexecution.domain.GitDiffSummary;
import com.matrixcode.modelgateway.domain.ModelRequestRecord;
import com.matrixcode.modelgateway.domain.RoleModelBinding;
import com.matrixcode.realtime.domain.ProjectEvent;
import com.matrixcode.roleagent.domain.RoleAgentConfig;
import com.matrixcode.workflow.domain.WorkflowEvent;
import com.matrixcode.workflow.domain.WorkflowItem;

import java.util.List;
import java.util.Map;

/**
 * 工作台旧快照兼容存储接口。
 *
 * <p>作用域：文件模式和早期 JDBC 快照兼容层；场景：在领域表迁移前后保持工作台状态可恢复。</p>
 */
public interface WorkbenchStateStore {

    /**
     * 读取完整工作台快照。
     */
    WorkbenchStateSnapshot load();

    /**
     * 保存文档版本窗口。
     */
    void saveDocuments(List<DocumentVersion> documents);

    /**
     * 保存项目 Bug 窗口。
     */
    void saveBugs(List<ProjectBug> bugs);

    /**
     * 保存部署目标窗口。
     */
    void saveDeploymentTargets(List<DeploymentTarget> targets);

    /**
     * 保存部署操作窗口。
     */
    void saveDeploymentOperations(Map<String, List<DeploymentOperationRecord>> operations);

    /**
     * 保存部署健康检查窗口。
     */
    void saveDeploymentHealthChecks(Map<String, List<DeploymentHealthCheck>> checks);

    /**
     * 保存 Compose 环境窗口。
     */
    void saveComposeEnvironments(List<ComposeEnvironment> environments);

    /**
     * 保存 Compose 操作窗口。
     */
    void saveComposeOperations(Map<String, List<ComposeOperationRecord>> operations);

    /**
     * 保存角色模型绑定窗口。
     */
    void saveModelBindings(List<RoleModelBinding> bindings);

    /**
     * 保存模型请求窗口。
     */
    void saveModelRequests(Map<String, List<ModelRequestRecord>> requests);

    /**
     * 保存项目事件窗口。
     */
    void saveProjectEvents(Map<String, List<ProjectEvent>> events);

    /**
     * 保存本地文件操作窗口。
     */
    void saveFileOperations(Map<String, List<FileOperationRecord>> operations);

    /**
     * 保存项目 Git Diff 摘要窗口。
     */
    void saveGitDiffSummaries(Map<String, GitDiffSummary> summaries);

    /**
     * 保存工作流阶段窗口。
     */
    void saveWorkflowItems(List<WorkflowItem> items);

    /**
     * 保存工作流事件窗口。
     */
    void saveWorkflowEvents(Map<String, List<WorkflowEvent>> events);

    /**
     * 保存角色智能体配置窗口。
     */
    void saveRoleAgentConfigs(List<RoleAgentConfig> configs);

    /**
     * 保存产品验收状态窗口。
     */
    void saveAcceptances(Map<String, WorkbenchStateSnapshot.AcceptanceState> acceptances);
}
