package com.matrixcode.context.application;

import com.matrixcode.context.domain.ContextBlock;
import com.matrixcode.context.domain.ContextManifest;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ContextEngine {

    public ContextManifest build(String role, List<ContextBlock> candidates) {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("角色不能为空");
        }
        if (candidates == null) {
            throw new IllegalArgumentException("候选上下文不能为空");
        }

        var allowed = candidates.stream()
                .filter(ContextBlock::allowedByGate)
                .toList();
        var omitted = candidates.stream()
                .filter(block -> !block.allowedByGate())
                .map(ContextBlock::type)
                .toList();
        return new ContextManifest(role, allowed, omitted);
    }
}
