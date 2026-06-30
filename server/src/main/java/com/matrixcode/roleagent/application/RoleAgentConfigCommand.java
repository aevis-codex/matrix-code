package com.matrixcode.roleagent.application;

import com.matrixcode.roleagent.domain.RoleAgentConfig;

public record RoleAgentConfigCommand(
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
        String cacheScopeStrategy
) {
    public RoleAgentConfigCommand(
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
            String volatileSuffixStrategy
    ) {
        this(
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
                RoleAgentConfig.DEFAULT_CACHE_SCOPE_STRATEGY
        );
    }

    public RoleAgentConfigCommand {
        cachePolicyId = cachePolicyId == null || cachePolicyId.isBlank()
                ? RoleAgentConfig.DEFAULT_CACHE_POLICY_ID
                : cachePolicyId.trim();
        volatileSuffixStrategy = volatileSuffixStrategy == null || volatileSuffixStrategy.isBlank()
                ? RoleAgentConfig.DEFAULT_VOLATILE_SUFFIX_STRATEGY
                : volatileSuffixStrategy.trim();
        cacheScopeStrategy = cacheScopeStrategy == null || cacheScopeStrategy.isBlank()
                ? RoleAgentConfig.DEFAULT_CACHE_SCOPE_STRATEGY
                : cacheScopeStrategy.trim().toLowerCase();
    }
}
