package com.matrixcode.modelgateway.domain;

/**
 * 描述一次模型请求中的 Prompt 分区。
 *
 * <p>该领域对象只保存低敏结构元数据：分区 key、展示名称、稳定性、是否允许进入供应商缓存前缀
 * 和估算 token。它不承载 prompt 正文、用户输入、向量召回正文或工具输出，避免审计数据泄露敏感上下文。</p>
 */
public record PromptPartition(
        String key,
        String label,
        String stability,
        boolean cacheable,
        long estimatedTokens
) {
    /**
     * 归一化并校验 Prompt 分区。
     *
     * <p>稳定性只允许 `STABLE` 或 `VOLATILE`；动态分区禁止声明为可缓存，避免后续编排器错误地把
     * 角色提示词、用户指令或召回上下文放入稳定前缀缓存区域。</p>
     */
    public PromptPartition {
        key = requireText(key, "Prompt 分区 key 不能为空");
        label = requireText(label, "Prompt 分区名称不能为空");
        stability = requireText(stability, "Prompt 分区稳定性不能为空").toUpperCase();
        if (!"STABLE".equals(stability) && !"VOLATILE".equals(stability)) {
            throw new IllegalArgumentException("Prompt 分区稳定性只允许 STABLE 或 VOLATILE");
        }
        if ("VOLATILE".equals(stability) && cacheable) {
            throw new IllegalArgumentException("动态 Prompt 分区不能标记为可缓存");
        }
        if (estimatedTokens < 0) {
            throw new IllegalArgumentException("Prompt 分区估算 token 不能为负数");
        }
    }

    /**
     * 校验必填文本并去除首尾空白。
     *
     * <p>该方法只处理低敏枚举/标签字段，不接收 prompt 正文。</p>
     */
    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
