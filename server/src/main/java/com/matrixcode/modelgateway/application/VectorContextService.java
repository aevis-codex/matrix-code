package com.matrixcode.modelgateway.application;

import com.matrixcode.context.domain.ContextBlock;
import com.matrixcode.modelgateway.domain.ModelRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Service
public class VectorContextService implements VectorContextRetriever {

    private final ModelGatewayProperties properties;
    private final EmbeddingClient embeddingClient;
    private final VectorContextStore store;

    @Autowired
    public VectorContextService(
            ModelGatewayProperties properties,
            EmbeddingClient embeddingClient,
            VectorContextStore store
    ) {
        this.properties = properties;
        this.embeddingClient = embeddingClient;
        this.store = store;
    }

    @Override
    public List<ContextBlock> recall(String projectId, ModelRole role, String instruction) {
        if (!properties.getVectorContext().isEnabled()) {
            return List.of();
        }
        var embedding = embeddingClient.embed(instruction);
        return store.search(projectId, role, embedding, properties.getVectorContext().getTopK()).stream()
                .map(hit -> new ContextBlock(
                        "VECTOR_CONTEXT",
                        "%s：%s".formatted(hit.type(), hit.summary()),
                        true
                ))
                .toList();
    }

    public void upsert(VectorContextDocument document) {
        if (!properties.getVectorContext().isEnabled()) {
            return;
        }
        var embedding = embeddingClient.embed(document.summary());
        store.upsert(new VectorContextEntry(
                entryId(document),
                document.projectId(),
                document.role(),
                document.type(),
                document.summary(),
                embedding
        ));
    }

    private String entryId(VectorContextDocument document) {
        var seed = "%s|%s|%s|%s".formatted(
                document.projectId(),
                document.role().name(),
                document.type(),
                document.summary()
        );
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
