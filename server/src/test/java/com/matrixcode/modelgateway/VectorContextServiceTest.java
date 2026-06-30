package com.matrixcode.modelgateway;

import com.matrixcode.context.domain.ContextBlock;
import com.matrixcode.modelgateway.application.EmbeddingClient;
import com.matrixcode.modelgateway.application.InMemoryVectorContextStore;
import com.matrixcode.modelgateway.application.ModelGatewayProperties;
import com.matrixcode.modelgateway.application.VectorContextDocument;
import com.matrixcode.modelgateway.application.VectorContextService;
import com.matrixcode.modelgateway.domain.ModelRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VectorContextServiceTest {

    @Test
    void 启用后基于Embedding召回允许注入模型请求的向量上下文() {
        var properties = new ModelGatewayProperties();
        properties.getVectorContext().setEnabled(true);
        properties.getVectorContext().setTopK(2);
        var embeddings = new FixedEmbeddingClient();
        var store = new InMemoryVectorContextStore();
        var service = new VectorContextService(properties, embeddings, store);
        service.upsert(new VectorContextDocument(
                "demo",
                ModelRole.DEVELOPER,
                "HANDOFF_DOCUMENT",
                "交接文档要求包含部署步骤和回滚说明"
        ));

        List<ContextBlock> blocks = service.recall("demo", ModelRole.DEVELOPER, "生成交接文档");

        assertThat(blocks).singleElement().satisfies(block -> {
            assertThat(block.type()).isEqualTo("VECTOR_CONTEXT");
            assertThat(block.summary()).contains("HANDOFF_DOCUMENT", "部署步骤和回滚说明");
            assertThat(block.allowedByGate()).isTrue();
        });
        assertThat(embeddings.inputs()).contains("交接文档要求包含部署步骤和回滚说明", "生成交接文档");
    }

    @Test
    void 关闭时不调用Embedding也不召回上下文() {
        var properties = new ModelGatewayProperties();
        properties.getVectorContext().setEnabled(false);
        var embeddings = new FixedEmbeddingClient();
        var service = new VectorContextService(properties, embeddings, new InMemoryVectorContextStore());

        assertThat(service.recall("demo", ModelRole.PRODUCT, "需求讨论")).isEmpty();
        assertThat(embeddings.inputs()).isEmpty();
    }

    private static final class FixedEmbeddingClient implements EmbeddingClient {
        private final java.util.ArrayList<String> inputs = new java.util.ArrayList<>();

        @Override
        public List<Float> embed(String input) {
            inputs.add(input);
            return List.of(1.0f, 0.0f, 0.0f);
        }

        private List<String> inputs() {
            return List.copyOf(inputs);
        }
    }
}
