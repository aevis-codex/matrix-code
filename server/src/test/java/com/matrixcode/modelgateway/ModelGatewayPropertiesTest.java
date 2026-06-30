package com.matrixcode.modelgateway;

import com.matrixcode.modelgateway.application.ModelGatewayProperties;
import com.matrixcode.modelgateway.application.ModelProviderRegistry;
import com.matrixcode.modelgateway.application.RoleModelBindingService;
import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelGatewayPropertiesTest {

    @Test
    void 配置国内模型供应商和角色默认绑定() {
        var properties = new ModelGatewayProperties();
        properties.provider("deepseek").setName("DeepSeek");
        properties.provider("deepseek").setBaseUrl("https://api.deepseek.com");
        properties.provider("deepseek").setApiKeySource("MATRIXCODE_DEEPSEEK_API_KEY");
        properties.provider("deepseek").setEnabled(true);
        properties.roleDefault(ModelRole.DEVELOPER).setProviderId("deepseek");
        properties.roleDefault(ModelRole.DEVELOPER).setModel("deepseek-chat");

        var registry = new ModelProviderRegistry(properties);
        var bindings = new RoleModelBindingService(registry, new InMemoryWorkbenchStateStore(), properties);

        assertThat(registry.enabledProviders()).extracting("id").contains("deepseek");
        assertThat(bindings.require("demo", ModelRole.DEVELOPER)).satisfies(binding -> {
            assertThat(binding.providerId()).isEqualTo("deepseek");
            assertThat(binding.model()).isEqualTo("deepseek-chat");
        });
    }

    @Test
    void Milvus默认配置使用用户确认的地址和千问EmbeddingV4() {
        var properties = new ModelGatewayProperties();

        assertThat(properties.getMilvus().getHost()).isEqualTo("127.0.0.1");
        assertThat(properties.getMilvus().getPort()).isEqualTo(19530);
        assertThat(properties.getMilvus().getDatabase()).isEqualTo("matrix_code");
        assertThat(properties.getMilvus().getCollection()).isEqualTo("matrixcode_context_chunks_v2");
        assertThat(properties.getMilvus().getLoadTimeoutMs()).isEqualTo(120_000L);
        assertThat(properties.getEmbedding().getProviderId()).isEqualTo("qwen");
        assertThat(properties.getEmbedding().getModel()).isEqualTo("text-embedding-v4");
        assertThat(properties.getVectorContext().getStore()).isEqualTo("memory");
        assertThat(properties.getVectorContext().getDimension()).isEqualTo(1024);
        assertThat(properties.getVectorContext().getTopK()).isEqualTo(3);
    }
}
