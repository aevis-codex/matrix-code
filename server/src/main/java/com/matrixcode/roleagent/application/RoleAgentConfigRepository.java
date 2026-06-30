package com.matrixcode.roleagent.application;

import com.matrixcode.roleagent.domain.RoleAgentConfig;

import java.util.List;

/**
 * 角色智能体配置仓储接口。
 *
 * <p>作用域：角色 Agent 配置中心；场景：保存系统提示词、用户提示词、模型预算、样式和缓存策略。</p>
 */
public interface RoleAgentConfigRepository {

    /**
     * 读取所有角色智能体配置。
     */
    List<RoleAgentConfig> load();

    /**
     * 保存角色智能体配置集合。
     */
    void save(List<RoleAgentConfig> configs);
}
