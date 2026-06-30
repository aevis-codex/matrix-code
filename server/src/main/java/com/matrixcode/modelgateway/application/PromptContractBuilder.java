package com.matrixcode.modelgateway.application;

import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.modelgateway.domain.PromptContract;
import com.matrixcode.modelgateway.domain.PromptPartition;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@Component
public class PromptContractBuilder {

    public PromptContract build(ModelRole role, String model, String toolContractVersion) {
        return build(role, model, toolContractVersion, "");
    }

    public PromptContract build(ModelRole role, String model, String toolContractVersion, String roleSystemPrompt) {
        if (role == null) {
            throw new IllegalArgumentException("模型角色不能为空");
        }
        requireText(model, "模型名称不能为空");
        var normalizedToolContractVersion = requireText(toolContractVersion, "工具契约版本不能为空");
        var stablePlatformPrefix = stablePlatformPrefix(role, normalizedToolContractVersion);
        var systemPrefix = stablePlatformPrefix + rolePromptSuffix(roleSystemPrompt);
        var hashSource = role.name() + "\n" + normalizedToolContractVersion + "\n" + stablePlatformPrefix;
        var partitions = promptPartitions(stablePlatformPrefix, normalizedToolContractVersion, roleSystemPrompt);
        return new PromptContract(
                role,
                model.trim(),
                normalizedToolContractVersion,
                systemPrefix,
                stableHash(hashSource),
                estimateTokens(stablePlatformPrefix),
                PromptContract.DEFAULT_PROMPT_PARTITION_POLICY_ID,
                "",
                partitions,
                0,
                0
        );
    }

    /**
     * 构造供应商 prompt cache 最应复用的平台稳定前缀。
     *
     * <p>该前缀只包含 MatrixCode 固定规则和工具契约版本，不包含模型名、用户指令、向量召回、
     * 文件内容、工具输出或角色自定义提示词。角色提示词仍会追加到完整 system prompt 中，但不参与
     * 稳定前缀 hash 和本地缓存命中估算，避免配置微调导致全部前缀被判定为 cache miss。</p>
     */
    private String stablePlatformPrefix(ModelRole role, String toolContractVersion) {
        return """
                MatrixCode 团队交付平台稳定系统前缀。
                当前角色：%s。
                平台规则：只消费经过上下文门禁允许的冻结产物、当前状态事件和显式检索证据。
                输出契约：使用中文，明确列出依据、结论、风险和下一步动作。
                工具契约版本：%s。
                缓存策略：stable-platform-prefix-v1。
                """.formatted(role.displayName(), toolContractVersion).strip();
    }

    private String rolePromptSuffix(String roleSystemPrompt) {
        if (roleSystemPrompt == null || roleSystemPrompt.isBlank()) {
            return "";
        }
        return "\n\n角色配置提示词：\n" + roleSystemPrompt.strip();
    }

    /**
     * 生成模型请求的低敏 Prompt 分区清单。
     *
     * <p>该清单只描述分区边界、稳定性和估算 token，不保存各分区正文。稳定分区会尽量保持在
     * system prompt 最前面，动态槽位后置，贴合 DeepSeek KV Cache 对稳定前缀的利用方式。</p>
     */
    private List<PromptPartition> promptPartitions(
            String stablePlatformPrefix,
            String toolContractVersion,
            String roleSystemPrompt
    ) {
        return List.of(
                new PromptPartition(
                        "PLATFORM_STABLE_PREFIX",
                        "平台稳定前缀",
                        "STABLE",
                        true,
                        estimateTokens(stablePlatformPrefix)
                ),
                new PromptPartition(
                        "TOOL_CONTRACT_VERSION",
                        "工具契约版本",
                        "STABLE",
                        true,
                        estimateTokens(toolContractVersion)
                ),
                new PromptPartition(
                        "ROLE_SYSTEM_PROMPT",
                        "角色系统提示词",
                        "VOLATILE",
                        false,
                        estimateTokens(roleSystemPrompt == null ? "" : roleSystemPrompt)
                ),
                new PromptPartition(
                        "CONTEXT_MANIFEST_SLOT",
                        "上下文清单槽位",
                        "VOLATILE",
                        false,
                        0
                ),
                new PromptPartition(
                        "USER_INSTRUCTION_SLOT",
                        "用户指令槽位",
                        "VOLATILE",
                        false,
                        0
                )
        );
    }

    private static String stableHash(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            var firstBytes = new byte[16];
            System.arraycopy(digest, 0, firstBytes, 0, firstBytes.length);
            return HexFormat.of().formatHex(firstBytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 运行时缺少 SHA-256", exception);
        }
    }

    private static long estimateTokens(String value) {
        return Math.max(1L, value.length() / 2L);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
