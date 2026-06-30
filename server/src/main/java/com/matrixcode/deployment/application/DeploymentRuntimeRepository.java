package com.matrixcode.deployment.application;

import com.matrixcode.deployment.domain.ComposeEnvironment;
import com.matrixcode.deployment.domain.ComposeOperationRecord;
import com.matrixcode.deployment.domain.DeploymentHealthCheck;
import com.matrixcode.deployment.domain.DeploymentOperationRecord;

import java.util.List;
import java.util.Map;

/**
 * 部署运行态仓储接口。
 *
 * <p>作用域：部署操作、健康检查和 Compose 环境；场景：运维工作台恢复发布、回滚、日志和环境状态。</p>
 */
public interface DeploymentRuntimeRepository {

    /**
     * 读取部署和回滚操作记录。
     */
    Map<String, List<DeploymentOperationRecord>> loadDeploymentOperations();

    /**
     * 保存部署和回滚操作记录。
     */
    void saveDeploymentOperations(Map<String, List<DeploymentOperationRecord>> operations);

    /**
     * 读取部署健康检查记录。
     */
    Map<String, List<DeploymentHealthCheck>> loadDeploymentHealthChecks();

    /**
     * 保存部署健康检查记录。
     */
    void saveDeploymentHealthChecks(Map<String, List<DeploymentHealthCheck>> checks);

    /**
     * 读取 Compose 环境配置。
     */
    List<ComposeEnvironment> loadComposeEnvironments();

    /**
     * 保存 Compose 环境配置。
     */
    void saveComposeEnvironments(List<ComposeEnvironment> environments);

    /**
     * 读取 Compose 操作记录。
     */
    Map<String, List<ComposeOperationRecord>> loadComposeOperations();

    /**
     * 保存 Compose 操作记录。
     */
    void saveComposeOperations(Map<String, List<ComposeOperationRecord>> operations);
}
