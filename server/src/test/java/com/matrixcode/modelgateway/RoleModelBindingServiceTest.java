package com.matrixcode.modelgateway;

import com.matrixcode.modelgateway.application.ModelProviderRegistry;
import com.matrixcode.modelgateway.application.RoleModelBindingService;
import com.matrixcode.modelgateway.domain.ModelProtocol;
import com.matrixcode.modelgateway.domain.ModelProvider;
import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleModelBindingServiceTest {

    @Test
    void 四个角色默认绑定本地模型() {
        var service = new RoleModelBindingService(new ModelProviderRegistry());

        assertThat(service.bindings("demo")).hasSize(4);
        assertThat(service.require("demo", ModelRole.PRODUCT).model()).isEqualTo("matrixcode-local-product");
        assertThat(service.require("demo", ModelRole.DEVELOPER).contextBudgetTokens()).isEqualTo(32_000);
    }

    @Test
    void 可以为角色绑定已启用供应商模型() {
        var registry = new ModelProviderRegistry();
        registry.upsert(new ModelProvider("qwen", "Qwen 兼容", ModelProtocol.OPENAI_COMPATIBLE,
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "MATRIXCODE_QWEN_KEY", true));
        var service = new RoleModelBindingService(registry);

        var binding = service.bind("demo", ModelRole.TESTER, "qwen", "qwen-max", "CNY", 0.15, 1.5, 6.0, 48_000, "tools-v2");

        assertThat(binding.providerId()).isEqualTo("qwen");
        assertThat(binding.model()).isEqualTo("qwen-max");
        assertThat(service.require("demo", ModelRole.TESTER).toolContractVersion()).isEqualTo("tools-v2");
    }

    @Test
    void 服务重建后恢复角色模型绑定() {
        var store = new InMemoryWorkbenchStateStore();
        var registry = new ModelProviderRegistry();
        registry.upsert(new ModelProvider("qwen", "Qwen 兼容", ModelProtocol.OPENAI_COMPATIBLE,
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "MATRIXCODE_QWEN_KEY", true));
        var firstService = new RoleModelBindingService(registry, store);
        firstService.bind("demo", ModelRole.TESTER, "qwen", "qwen-max", "CNY", 0.15, 1.5, 6.0, 48_000, "tools-v2");

        var secondService = new RoleModelBindingService(registry, store);

        assertThat(secondService.require("demo", ModelRole.TESTER).model()).isEqualTo("qwen-max");
        assertThat(secondService.require("demo", ModelRole.TESTER).toolContractVersion()).isEqualTo("tools-v2");
    }

    @Test
    void 不能绑定禁用供应商或空角色模型() {
        var registry = new ModelProviderRegistry();
        registry.upsert(new ModelProvider("disabled", "停用供应商", ModelProtocol.OPENAI_COMPATIBLE,
                "https://example.com/v1", "MATRIXCODE_DISABLED_KEY", false));
        var service = new RoleModelBindingService(registry);

        assertThatThrownBy(() -> service.bind("demo", ModelRole.PRODUCT, "disabled", "model", "CNY", 0.1, 1.0, 2.0, 32_000, "tools-v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("模型供应商未启用");
        assertThatThrownBy(() -> service.bind("demo", ModelRole.PRODUCT, "local-deterministic", " ", "CNY", 0.0, 0.0, 0.0, 32_000, "tools-v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("模型名称不能为空");
    }
}
