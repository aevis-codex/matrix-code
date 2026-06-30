package com.matrixcode.runtimecheck.domain;

public record RuntimeCheckItem(
        String key,
        String label,
        RuntimeCheckStatus status,
        String detail,
        boolean blocking
) {
    public RuntimeCheckItem {
        key = key == null ? "" : key.trim();
        label = label == null ? "" : label.trim();
        detail = detail == null ? "" : detail.trim();
    }
}
