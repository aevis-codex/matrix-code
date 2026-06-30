package com.matrixcode.modelgateway.application;

import com.matrixcode.modelgateway.domain.ModelRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
@ConditionalOnProperty(prefix = "matrixcode.model-gateway.vector-context", name = "store", havingValue = "memory", matchIfMissing = true)
public class InMemoryVectorContextStore implements VectorContextStore {

    private final CopyOnWriteArrayList<VectorContextEntry> entries = new CopyOnWriteArrayList<>();

    @Override
    public void upsert(VectorContextEntry entry) {
        entries.removeIf(existing -> existing.id().equals(entry.id()));
        entries.add(entry);
    }

    @Override
    public List<VectorContextHit> search(String projectId, ModelRole role, List<Float> embedding, int topK) {
        if (embedding == null || embedding.isEmpty() || topK <= 0) {
            return List.of();
        }
        return entries.stream()
                .filter(entry -> entry.projectId().equals(projectId))
                .filter(entry -> entry.role() == role)
                .map(entry -> new VectorContextHit(entry.type(), entry.summary(), cosine(entry.embedding(), embedding)))
                .filter(hit -> hit.score() > 0)
                .sorted(Comparator.comparingDouble(VectorContextHit::score).reversed())
                .limit(topK)
                .toList();
    }

    private double cosine(List<Float> left, List<Float> right) {
        var length = Math.min(left.size(), right.size());
        if (length == 0) {
            return 0;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (var index = 0; index < length; index++) {
            var leftValue = left.get(index);
            var rightValue = right.get(index);
            dot += leftValue * rightValue;
            leftNorm += leftValue * leftValue;
            rightNorm += rightValue * rightValue;
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
