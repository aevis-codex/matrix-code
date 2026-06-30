package com.matrixcode.deployment.application;

import com.matrixcode.deployment.domain.DeploymentTarget;

import java.util.List;

/**
 * 部署目标仓储接口。
 *
 * <p>作用域：运维部署目标；场景：保存环境 URL、SSH 地址、健康检查地址和回滚说明。</p>
 */
public interface DeploymentTargetRepository {

    /**
     * 读取所有部署目标。
     */
    List<DeploymentTarget> load();

    /**
     * 保存部署目标集合。
     */
    void save(List<DeploymentTarget> targets);
}
