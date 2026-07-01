package com.matrixcode.modelgateway.domain;

import com.matrixcode.context.domain.ContextBlock;

import java.util.Optional;
import java.util.Set;

/**
 * 单次角色模型请求的运行选项。
 *
 * <p>作用域：模型网关运行层；场景：前端 Agent Composer 发起请求时，把本次会话的模型覆盖、
 * 工具权限、协作方式和推理力度传入后端。该记录只保存低敏配置，不接收 API Key、完整工具输出或文件正文。</p>
 */
public record ModelRequestRuntimeOptions(
        String providerId,
        String model,
        String approvalMode,
        String reasoningEffort,
        boolean planMode,
        boolean goalMode,
        boolean tokenEconomy
) {
    private static final Set<String> APPROVAL_MODES = Set.of("ask", "auto", "yolo");
    private static final Set<String> REASONING_EFFORTS = Set.of("auto", "high", "max");

    public ModelRequestRuntimeOptions {
        providerId = optionalText(providerId);
        model = optionalText(model);
        approvalMode = normalizedChoice(approvalMode, "auto", APPROVAL_MODES, "工具权限模式不支持");
        reasoningEffort = normalizedChoice(reasoningEffort, "auto", REASONING_EFFORTS, "推理力度不支持");
        if (providerId.isBlank() != model.isBlank()) {
            throw new IllegalArgumentException("模型覆盖必须同时提供供应商 ID 和模型名称");
        }
    }

    /**
     * 返回兼容旧调用的默认运行选项。
     */
    public static ModelRequestRuntimeOptions defaults() {
        return new ModelRequestRuntimeOptions("", "", "auto", "auto", false, false, false);
    }

    /**
     * 判断本次请求是否显式选择了模型。
     */
    public boolean hasModelOverride() {
        return !providerId.isBlank() && !model.isBlank();
    }

    /**
     * 判断是否需要把 Composer 运行态写入上下文清单。
     */
    public boolean hasComposerRuntimeContext() {
        return planMode || goalMode || tokenEconomy || !"auto".equals(approvalMode) || !"auto".equals(reasoningEffort) || hasModelOverride();
    }

    /**
     * 构建模型可见的低敏运行态上下文块。
     */
    public Optional<ContextBlock> composerContextBlock() {
        if (!hasComposerRuntimeContext()) {
            return Optional.empty();
        }
        var intentLabels = new java.util.ArrayList<String>();
        if (planMode) {
            intentLabels.add("计划");
        }
        if (goalMode) {
            intentLabels.add("目标");
        }
        if (tokenEconomy) {
            intentLabels.add("省 token");
        }
        var summaryParts = new java.util.ArrayList<String>();
        summaryParts.add("工具权限：" + approvalModeLabel());
        summaryParts.add("推理力度：" + reasoningEffort);
        summaryParts.add("协作方式：" + (intentLabels.isEmpty() ? "默认" : String.join("、", intentLabels)));
        if (hasModelOverride()) {
            summaryParts.add("模型覆盖：" + providerId + "/" + model);
        }
        return Optional.of(new ContextBlock("COMPOSER_RUNTIME", String.join("；", summaryParts), true));
    }

    /**
     * 判断是否允许注入会快速变化的动态上下文。
     */
    public boolean dynamicContextAllowed() {
        return !tokenEconomy;
    }

    /**
     * 构建模型指令中的执行策略补充。
     */
    public String instructionPolicy() {
        var policyParts = new java.util.ArrayList<String>();
        if (planMode) {
            policyParts.add("计划模式：只输出分析、风险、步骤和待确认项，不生成已执行结论，不要求写入文件或执行命令");
        }
        if (goalMode) {
            policyParts.add("目标模式：围绕用户目标给出可持续推进的下一步、验收点和阻塞项");
        }
        if (tokenEconomy) {
            policyParts.add("省 token：优先复用稳定系统前缀，只依赖必要阶段信息，不展开最近文档、事件和向量召回明细");
        }
        policyParts.add("工具权限：" + switch (approvalMode) {
            case "ask" -> "任何工具、写入或命令动作都必须先请求人工确认";
            case "yolo" -> "允许非危险本地 Shell 动作自动推进，但危险、远程、写入和凭据相关动作仍需人工确认";
            default -> "可自动执行已被系统判定为安全的动作，其他动作请求人工确认";
        });
        policyParts.add("推理力度：" + switch (reasoningEffort) {
            case "high" -> "提高推理深度，保留关键取舍";
            case "max" -> "使用最高推理深度，先完整检查依赖、风险和验证路径";
            default -> "使用供应商默认推理力度";
        });
        return String.join("；", policyParts);
    }

    /**
     * 返回供应商请求参数可使用的推理力度；auto 表示不显式覆盖供应商默认值。
     */
    public Optional<String> explicitReasoningEffort() {
        return "auto".equals(reasoningEffort) ? Optional.empty() : Optional.of(reasoningEffort);
    }

    private String approvalModeLabel() {
        return switch (approvalMode) {
            case "ask" -> "问询";
            case "yolo" -> "Yolo";
            default -> "自动";
        };
    }

    private static String normalizedChoice(String value, String defaultValue, Set<String> allowed, String message) {
        var normalized = optionalText(value).isBlank() ? defaultValue : optionalText(value).toLowerCase(java.util.Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException(message + "：" + value);
        }
        return normalized;
    }

    private static String optionalText(String value) {
        return value == null ? "" : value.trim();
    }
}
