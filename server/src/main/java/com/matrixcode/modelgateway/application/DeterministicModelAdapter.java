package com.matrixcode.modelgateway.application;

import com.matrixcode.agent.application.LocalProductDraftAgent;
import com.matrixcode.agent.domain.ProductDraftRequest;
import com.matrixcode.modelgateway.domain.ModelCompletionResult;
import com.matrixcode.modelgateway.domain.ModelProtocol;
import com.matrixcode.modelgateway.domain.ModelProvider;
import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.modelgateway.domain.PromptContract;
import com.matrixcode.modelgateway.domain.RoleModelBinding;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
public class DeterministicModelAdapter implements ModelCompletionClient {

    private final LocalProductDraftAgent productDraftAgent;

    public DeterministicModelAdapter(LocalProductDraftAgent productDraftAgent) {
        this.productDraftAgent = productDraftAgent;
    }

    @Override
    public boolean supports(ModelProtocol protocol) {
        return protocol == ModelProtocol.LOCAL;
    }

    @Override
    public ModelCompletionResult complete(
            ModelProvider provider,
            RoleModelBinding binding,
            PromptContract contract,
            String instruction,
            String cacheScopeId
    ) {
        return ModelCompletionResult.withoutProviderUsage(complete(binding.role(), instruction));
    }

    /**
     * 生成本地确定性回答。
     *
     * <p>该适配器用于测试和离线演示，不伪造供应商 token 字段；模型网关会继续通过
     * `PromptCacheEstimator` 计算稳定前缀估算值。</p>
     */
    String complete(ModelRole role, String instruction) {
        if (role == null) {
            throw new IllegalArgumentException("模型角色不能为空");
        }
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("模型请求指令不能为空");
        }
        if (role == ModelRole.PRODUCT) {
            return productDraftAgent.generate(new ProductDraftRequest(instruction)).documents().stream()
                    .map(document -> document.title() + "\n\n" + document.content())
                    .collect(Collectors.joining("\n\n---\n\n"));
        }

        return """
                # %s模型建议

                用户指令：
                %s

                建议：
                - 保持当前阶段的冻结产物和上下文清单一致。
                - 先完成可验证的小步交付，再推进到下一角色。
                - 需要人工确认的动作继续保留审批记录。
                """.formatted(role.displayName(), instruction.strip());
    }
}
