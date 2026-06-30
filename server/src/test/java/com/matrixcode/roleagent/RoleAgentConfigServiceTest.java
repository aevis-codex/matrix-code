package com.matrixcode.roleagent;

import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.roleagent.application.RoleAgentConfigCommand;
import com.matrixcode.roleagent.application.RoleAgentConfigService;
import com.matrixcode.roleagent.domain.RoleAgentConfig;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleAgentConfigServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-25T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void 默认返回四个角色智能体配置() {
        var service = new RoleAgentConfigService(new InMemoryWorkbenchStateStore(), CLOCK);

        var configs = service.configs("demo");

        assertThat(configs)
                .extracting(RoleAgentConfig::role)
                .containsExactly(ModelRole.PRODUCT, ModelRole.DEVELOPER, ModelRole.TESTER, ModelRole.OPERATIONS);
        assertThat(configs)
                .filteredOn(config -> config.role() == ModelRole.DEVELOPER)
                .singleElement()
                .satisfies(config -> {
                    assertThat(config.displayName()).isEqualTo("开发智能体");
                    assertThat(config.systemPrompt()).contains("代码修改", "测试验证");
                    assertThat(config.themeColor()).isEqualTo("#2563eb");
                    assertThat(config.cachePolicyId()).isEqualTo("stable-platform-prefix-v1");
                    assertThat(config.volatileSuffixStrategy()).isEqualTo("role-prompt-and-dynamic-context");
                    assertThat(config.cacheScopeStrategy()).isEqualTo("provider-model");
                    assertThat(config.enabled()).isTrue();
                });
    }

    @Test
    void 更新开发智能体配置后可以从新服务实例恢复() {
        var store = new InMemoryWorkbenchStateStore();
        var service = new RoleAgentConfigService(store, CLOCK);

        service.update("demo", ModelRole.DEVELOPER, command(
                "开发智能体 Pro",
                "coding",
                "local-deterministic",
                "matrixcode-local-developer-pro",
                "tools-v2",
                "你是开发编码智能体，必须先读代码再修改。",
                "请基于以下任务输出计划并执行：{{instruction}}",
                "#0f766e",
                "Inter",
                15,
                2,
                true,
                "deepseek-prefix-v2",
                "stable-prefix-dynamic-tail",
                "provider-role"
        ));

        var restored = new RoleAgentConfigService(store, CLOCK).require("demo", ModelRole.DEVELOPER);

        assertThat(restored.displayName()).isEqualTo("开发智能体 Pro");
        assertThat(restored.model()).isEqualTo("matrixcode-local-developer-pro");
        assertThat(restored.toolContractVersion()).isEqualTo("tools-v2");
        assertThat(restored.systemPrompt()).contains("必须先读代码");
        assertThat(restored.userPromptTemplate()).contains("{{instruction}}");
        assertThat(restored.themeColor()).isEqualTo("#0f766e");
        assertThat(restored.fontFamily()).isEqualTo("Inter");
        assertThat(restored.fontSize()).isEqualTo(15);
        assertThat(restored.cachePolicyId()).isEqualTo("deepseek-prefix-v2");
        assertThat(restored.volatileSuffixStrategy()).isEqualTo("stable-prefix-dynamic-tail");
        assertThat(restored.cacheScopeStrategy()).isEqualTo("provider-role");
    }

    @Test
    void 拒绝空系统提示词和非法颜色() {
        var service = new RoleAgentConfigService(new InMemoryWorkbenchStateStore(), CLOCK);

        assertThatThrownBy(() -> service.update("demo", ModelRole.DEVELOPER, command(
                "开发智能体",
                "coding",
                "local-deterministic",
                "matrixcode-local-developer",
                "tools-v1",
                " ",
                "请执行：{{instruction}}",
                "#2563eb",
                "Inter",
                14,
                2,
                true
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("系统提示词不能为空");

        assertThatThrownBy(() -> service.update("demo", ModelRole.DEVELOPER, command(
                "开发智能体",
                "coding",
                "local-deterministic",
                "matrixcode-local-developer",
                "tools-v1",
                "你是开发编码智能体。",
                "请执行：{{instruction}}",
                "blue",
                "Inter",
                14,
                2,
                true
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("主题色必须是十六进制颜色");
    }

    private RoleAgentConfigCommand command(
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
            boolean enabled
    ) {
        return command(
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
                "stable-platform-prefix-v1",
                "role-prompt-and-dynamic-context",
                "provider-model"
        );
    }

    private RoleAgentConfigCommand command(
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
        return new RoleAgentConfigCommand(
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
                cacheScopeStrategy
        );
    }
}
