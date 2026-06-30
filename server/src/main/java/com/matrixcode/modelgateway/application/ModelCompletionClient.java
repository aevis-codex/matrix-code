package com.matrixcode.modelgateway.application;

import com.matrixcode.modelgateway.domain.ModelProtocol;
import com.matrixcode.modelgateway.domain.ModelProvider;
import com.matrixcode.modelgateway.domain.ModelCompletionResult;
import com.matrixcode.modelgateway.domain.ModelRequestRuntimeOptions;
import com.matrixcode.modelgateway.domain.PromptContract;
import com.matrixcode.modelgateway.domain.RoleModelBinding;

/**
 * 模型补全客户端接口。
 *
 * <p>作用域：模型网关；场景：屏蔽本地确定性模型和 OpenAI 兼容供应商之间的调用差异。</p>
 */
public interface ModelCompletionClient {

    /**
     * 判断当前客户端是否支持指定模型协议。
     */
    boolean supports(ModelProtocol protocol);

    /**
     * 执行一次模型补全请求。
     *
     * @param cacheScopeId MatrixCode 生成的缓存作用域，供应商客户端可用它隔离同项目同角色的
     *                     prefix cache，也可用于本地估算 key。
     */
    ModelCompletionResult complete(
            ModelProvider provider,
            RoleModelBinding binding,
            PromptContract contract,
            String instruction,
            String cacheScopeId
    );

    /**
     * 执行一次带 Composer 运行选项的模型补全请求。
     *
     * <p>默认实现兼容旧客户端；支持推理力度或供应商特定参数的客户端可覆盖该方法。</p>
     */
    default ModelCompletionResult complete(
            ModelProvider provider,
            RoleModelBinding binding,
            PromptContract contract,
            String instruction,
            String cacheScopeId,
            ModelRequestRuntimeOptions runtimeOptions
    ) {
        return complete(provider, binding, contract, instruction, cacheScopeId);
    }
}
