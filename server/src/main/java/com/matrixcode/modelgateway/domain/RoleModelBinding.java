package com.matrixcode.modelgateway.domain;

public record RoleModelBinding(
        String projectId,
        ModelRole role,
        String providerId,
        String model,
        String currency,
        double cacheHitPerMillion,
        double cacheMissInputPerMillion,
        double outputPerMillion,
        int contextBudgetTokens,
        String toolContractVersion
) {
    public RoleModelBinding {
        projectId = requireText(projectId, "项目编号不能为空");
        if (role == null) {
            throw new IllegalArgumentException("模型角色不能为空");
        }
        providerId = requireText(providerId, "供应商 ID 不能为空");
        model = requireText(model, "模型名称不能为空");
        currency = requireText(currency, "币种不能为空");
        toolContractVersion = requireText(toolContractVersion, "工具契约版本不能为空");
        if (contextBudgetTokens <= 0) {
            throw new IllegalArgumentException("上下文预算必须大于 0");
        }
        if (!Double.isFinite(cacheHitPerMillion)
                || !Double.isFinite(cacheMissInputPerMillion)
                || !Double.isFinite(outputPerMillion)) {
            throw new IllegalArgumentException("模型单价必须是有限数字");
        }
        if (cacheHitPerMillion < 0 || cacheMissInputPerMillion < 0 || outputPerMillion < 0) {
            throw new IllegalArgumentException("模型单价不能为负数");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
