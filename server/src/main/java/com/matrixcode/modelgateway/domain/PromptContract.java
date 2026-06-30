package com.matrixcode.modelgateway.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public record PromptContract(
        ModelRole role,
        String model,
        String toolContractVersion,
        String systemPrefix,
        String stablePrefixHash,
        long estimatedStablePrefixTokens,
        String promptPartitionPolicyId,
        String promptPartitionFingerprint,
        List<PromptPartition> partitions,
        int stablePartitionCount,
        int volatilePartitionCount
) {
    public static final String DEFAULT_PROMPT_PARTITION_POLICY_ID = "deepseek-reasonix-partitions-v1";

    public PromptContract(
            ModelRole role,
            String model,
            String toolContractVersion,
            String systemPrefix,
            String stablePrefixHash,
            long estimatedStablePrefixTokens
    ) {
        this(
                role,
                model,
                toolContractVersion,
                systemPrefix,
                stablePrefixHash,
                estimatedStablePrefixTokens,
                DEFAULT_PROMPT_PARTITION_POLICY_ID,
                "",
                List.of(new PromptPartition(
                        "PLATFORM_STABLE_PREFIX",
                        "平台稳定前缀",
                        "STABLE",
                        true,
                        Math.max(1L, estimatedStablePrefixTokens)
                )),
                1,
                0
        );
    }

    public PromptContract {
        if (role == null) {
            throw new IllegalArgumentException("模型角色不能为空");
        }
        model = requireText(model, "模型名称不能为空");
        toolContractVersion = requireText(toolContractVersion, "工具契约版本不能为空");
        systemPrefix = requireText(systemPrefix, "系统前缀不能为空");
        stablePrefixHash = requireText(stablePrefixHash, "稳定前缀哈希不能为空");
        if (estimatedStablePrefixTokens <= 0) {
            throw new IllegalArgumentException("稳定前缀 token 必须大于 0");
        }
        promptPartitionPolicyId = optionalText(promptPartitionPolicyId, DEFAULT_PROMPT_PARTITION_POLICY_ID);
        partitions = partitions == null || partitions.isEmpty()
                ? List.of(new PromptPartition(
                        "PLATFORM_STABLE_PREFIX",
                        "平台稳定前缀",
                        "STABLE",
                        true,
                        estimatedStablePrefixTokens
                ))
                : List.copyOf(partitions);
        stablePartitionCount = (int) partitions.stream().filter(partition -> "STABLE".equals(partition.stability())).count();
        volatilePartitionCount = (int) partitions.stream().filter(partition -> "VOLATILE".equals(partition.stability())).count();
        if (stablePartitionCount <= 0) {
            throw new IllegalArgumentException("Prompt 分区必须至少包含一个稳定分区");
        }
        promptPartitionFingerprint = promptPartitionFingerprint == null || promptPartitionFingerprint.isBlank()
                ? partitionFingerprint(promptPartitionPolicyId, partitions)
                : promptPartitionFingerprint.trim();
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String optionalText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /**
     * 生成只描述分区结构的 fingerprint。
     *
     * <p>fingerprint 不包含 prompt 正文、用户指令、向量召回正文或工具输出；角色提示词内容变化不会导致
     * 分区结构指纹变化，便于识别缓存边界是否被工程结构改变。</p>
     */
    private static String partitionFingerprint(String policyId, List<PromptPartition> partitions) {
        var source = new StringBuilder(policyId).append('\n');
        for (var partition : partitions) {
            source.append(partition.key())
                    .append('|')
                    .append(partition.stability())
                    .append('|')
                    .append(partition.cacheable())
                    .append('\n');
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.toString().getBytes(StandardCharsets.UTF_8));
            var firstBytes = new byte[16];
            System.arraycopy(digest, 0, firstBytes, 0, firstBytes.length);
            return HexFormat.of().formatHex(firstBytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 运行时缺少 SHA-256", exception);
        }
    }
}
