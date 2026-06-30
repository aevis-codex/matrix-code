package com.matrixcode.modelgateway.domain;

import com.matrixcode.context.domain.ContextManifest;
import com.matrixcode.usage.domain.UsageRecord;

import java.time.Instant;

public record ModelResponse(
        String requestId,
        String answer,
        ContextManifest contextManifest,
        UsageRecord usage,
        RoleModelBinding binding,
        PromptContract promptContract,
        Instant createdAt
) {
    public ModelResponse {
        requestId = requireText(requestId, "请求 ID 不能为空");
        answer = requireText(answer, "模型回答不能为空");
        if (contextManifest == null) {
            throw new IllegalArgumentException("上下文清单不能为空");
        }
        if (usage == null) {
            throw new IllegalArgumentException("用量记录不能为空");
        }
        if (binding == null) {
            throw new IllegalArgumentException("模型绑定不能为空");
        }
        if (promptContract == null) {
            throw new IllegalArgumentException("提示词契约不能为空");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("创建时间不能为空");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
