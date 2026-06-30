package com.matrixcode.agent.domain;

public record ProductDraftRequest(String requirement) {

    public ProductDraftRequest {
        if (requirement == null || requirement.isBlank()) {
            throw new IllegalArgumentException("产品需求不能为空");
        }
        requirement = requirement.trim();
    }
}
