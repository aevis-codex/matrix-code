package com.matrixcode.roleagent.domain;

import com.matrixcode.modelgateway.domain.ModelRole;

import java.time.Instant;

public record RoleAgentConfig(
        String projectId,
        ModelRole role,
        String displayName,
        String agentKind,
        String providerId,
        String model,
        String toolContractVersion,
        String systemPrompt,
        String userPromptTemplate,
        String themeColor,
        String fontFamily,
        int fontSize,
        int sortOrder,
        boolean enabled,
        String cachePolicyId,
        String volatileSuffixStrategy,
        String cacheScopeStrategy,
        Instant updatedAt
) {
    public static final String DEFAULT_CACHE_POLICY_ID = "stable-platform-prefix-v1";
    public static final String DEFAULT_VOLATILE_SUFFIX_STRATEGY = "role-prompt-and-dynamic-context";
    public static final String DEFAULT_CACHE_SCOPE_STRATEGY = "provider-model";

    public RoleAgentConfig(
            String projectId,
            ModelRole role,
            String displayName,
            String agentKind,
            String providerId,
            String model,
            String toolContractVersion,
            String systemPrompt,
            String userPromptTemplate,
            String themeColor,
            String fontFamily,
            int fontSize,
            int sortOrder,
            boolean enabled,
            String cachePolicyId,
            String volatileSuffixStrategy,
            Instant updatedAt
    ) {
        this(
                projectId,
                role,
                displayName,
                agentKind,
                providerId,
                model,
                toolContractVersion,
                systemPrompt,
                userPromptTemplate,
                themeColor,
                fontFamily,
                fontSize,
                sortOrder,
                enabled,
                cachePolicyId,
                volatileSuffixStrategy,
                DEFAULT_CACHE_SCOPE_STRATEGY,
                updatedAt
        );
    }

    public RoleAgentConfig {
        projectId = requireText(projectId, "项目编号不能为空");
        if (role == null) {
            throw new IllegalArgumentException("模型角色不能为空");
        }
        displayName = requireText(displayName, "智能体名称不能为空");
        agentKind = requireText(agentKind, "智能体类型不能为空");
        providerId = requireText(providerId, "模型供应商不能为空");
        model = requireText(model, "模型名称不能为空");
        toolContractVersion = requireText(toolContractVersion, "工具契约版本不能为空");
        systemPrompt = requireText(systemPrompt, "系统提示词不能为空");
        userPromptTemplate = requireText(userPromptTemplate, "用户提示词模板不能为空");
        themeColor = requireText(themeColor, "主题色不能为空");
        if (!themeColor.matches("#[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("主题色必须是十六进制颜色");
        }
        fontFamily = requireText(fontFamily, "字体不能为空");
        if (fontSize < 10 || fontSize > 28) {
            throw new IllegalArgumentException("字号必须在 10 到 28 之间");
        }
        if (sortOrder < 0) {
            throw new IllegalArgumentException("排序值不能为负数");
        }
        cachePolicyId = optionalText(cachePolicyId, DEFAULT_CACHE_POLICY_ID);
        volatileSuffixStrategy = optionalText(volatileSuffixStrategy, DEFAULT_VOLATILE_SUFFIX_STRATEGY);
        cacheScopeStrategy = normalizeCacheScopeStrategy(cacheScopeStrategy);
        updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String optionalText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String normalizeCacheScopeStrategy(String value) {
        var normalized = optionalText(value, DEFAULT_CACHE_SCOPE_STRATEGY).toLowerCase();
        return switch (normalized) {
            case "provider-model", "provider-role", "project-role" -> normalized;
            default -> throw new IllegalArgumentException("缓存作用域策略只能是 provider-model、provider-role 或 project-role");
        };
    }
}
