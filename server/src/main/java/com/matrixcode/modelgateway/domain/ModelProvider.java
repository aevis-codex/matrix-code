package com.matrixcode.modelgateway.domain;

public record ModelProvider(
        String id,
        String name,
        ModelProtocol protocol,
        String baseUrl,
        String apiKeySource,
        boolean enabled
) {
    public ModelProvider {
        id = requireText(id, "供应商 ID 不能为空");
        name = requireText(name, "供应商名称不能为空");
        if (protocol == null) {
            throw new IllegalArgumentException("供应商协议不能为空");
        }
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        apiKeySource = apiKeySource == null || apiKeySource.isBlank() ? "NONE" : apiKeySource.trim();
        if (protocol == ModelProtocol.OPENAI_COMPATIBLE && !baseUrl.startsWith("https://")) {
            throw new IllegalArgumentException("供应商基础地址必须使用 https");
        }
        if (apiKeySource.startsWith("sk-")) {
            throw new IllegalArgumentException("供应商 API Key 只允许填写环境变量名");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
