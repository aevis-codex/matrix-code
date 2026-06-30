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
