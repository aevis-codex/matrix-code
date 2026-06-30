package com.matrixcode.usage.domain;

public record ModelPrice(
        String model,
        String currency,
        double cacheHitPerMillion,
        double cacheMissInputPerMillion,
        double outputPerMillion
) {
    public ModelPrice {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("币种不能为空");
        }
        if (!Double.isFinite(cacheHitPerMillion)
                || !Double.isFinite(cacheMissInputPerMillion)
                || !Double.isFinite(outputPerMillion)) {
            throw new IllegalArgumentException("模型单价必须是有限数字");
        }
        if (cacheHitPerMillion < 0 || cacheMissInputPerMillion < 0 || outputPerMillion < 0) {
            throw new IllegalArgumentException("模型单价不能为负数");
        }
    }
}
