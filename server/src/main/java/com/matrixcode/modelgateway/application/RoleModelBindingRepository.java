package com.matrixcode.modelgateway.application;

import com.matrixcode.modelgateway.domain.RoleModelBinding;

import java.util.List;

/**
 * 角色模型绑定仓储接口。
 *
 * <p>作用域：模型网关配置持久化边界。主要场景是保存每个项目、每个角色默认使用的模型供应商和模型名称，
 * 让产品、开发、测试、部署等角色智能体可以在界面中独立切换模型。</p>
 */
public interface RoleModelBindingRepository {

    /**
     * 读取所有项目的角色模型绑定。
     *
     * <p>调用方会在内存中按项目和角色建立索引；仓储实现需要返回稳定顺序，便于测试、
     * 诊断和后续配置导出保持可重复。</p>
     */
    List<RoleModelBinding> load();

    /**
     * 保存当前内存中的角色模型绑定集合。
     *
     * <p>当前业务没有删除绑定的入口，因此仓储只负责逐条 upsert，不主动删除未出现在
     * 参数集合中的历史绑定。</p>
     */
    void save(List<RoleModelBinding> bindings);
}
