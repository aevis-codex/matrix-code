package com.matrixcode.modelgateway;

import com.matrixcode.modelgateway.application.ModelProviderRegistry;
import com.matrixcode.modelgateway.domain.ModelProtocol;
import com.matrixcode.modelgateway.domain.ModelProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelProviderRegistryTest {

    @Test
    void 默认包含本地确定性供应商() {
        var registry = new ModelProviderRegistry();

        var provider = registry.require("local-deterministic");

        assertThat(provider.name()).isEqualTo("本地确定性模型");
        assertThat(provider.protocol()).isEqualTo(ModelProtocol.LOCAL);
        assertThat(provider.enabled()).isTrue();
    }

    @Test
    void OpenAI兼容供应商必须使用Https地址且不能接收明文密钥() {
        var registry = new ModelProviderRegistry();

        assertThatThrownBy(() -> registry.upsert(new ModelProvider(
                "openai-compatible", "OpenAI 兼容", ModelProtocol.OPENAI_COMPATIBLE,
                "http://example.com/v1", "MATRIXCODE_OPENAI_KEY", true
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("供应商基础地址必须使用 https");

        assertThatThrownBy(() -> registry.upsert(new ModelProvider(
                "openai-compatible", "OpenAI 兼容", ModelProtocol.OPENAI_COMPATIBLE,
                "https://example.com/v1", "sk-live-key", true
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只允许填写环境变量名");
    }

    @Test
    void 禁用供应商仍可查询但不能作为可用供应商() {
        var registry = new ModelProviderRegistry();
        registry.upsert(new ModelProvider(
                "disabled-provider", "停用供应商", ModelProtocol.OPENAI_COMPATIBLE,
                "https://example.com/v1", "MATRIXCODE_DISABLED_KEY", false
        ));

        assertThat(registry.require("disabled-provider").enabled()).isFalse();
        assertThat(registry.enabledProviders()).extracting(ModelProvider::id)
                .containsExactly("local-deterministic");
    }
}
