package com.matrixcode.modelgateway.application;

public record VectorContextHit(
        String type,
        String summary,
        double score
) {
}
