package com.matrixcode.modelgateway.application;

import com.matrixcode.context.domain.ContextBlock;
import com.matrixcode.modelgateway.domain.ModelRole;

import java.util.List;

/**
 * 向量上下文召回接口。
 *
 * <p>作用域：模型请求前置上下文；场景：按项目、角色和用户指令召回可注入 Prompt 的上下文块。</p>
 */
public interface VectorContextRetriever {

    /**
     * 召回与当前指令最相关的上下文块。
     */
    List<ContextBlock> recall(String projectId, ModelRole role, String instruction);

    /**
     * 返回禁用向量召回的空实现。
     */
    static VectorContextRetriever disabled() {
        return (projectId, role, instruction) -> List.of();
    }
}
