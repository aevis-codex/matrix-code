package com.matrixcode.modelgateway;

import com.matrixcode.modelgateway.application.PromptCacheEstimator;
import com.matrixcode.modelgateway.application.PromptContractBuilder;
import com.matrixcode.modelgateway.domain.ModelRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PromptContractBuilderTest {

    @Test
    void 用户输入变化不会改变稳定前缀哈希() {
        var builder = new PromptContractBuilder();

        var first = builder.build(ModelRole.PRODUCT, "matrixcode-local-product", "tools-v1");
        var second = builder.build(ModelRole.PRODUCT, "matrixcode-local-product", "tools-v1");

        assertThat(first.stablePrefixHash()).isEqualTo(second.stablePrefixHash());
        assertThat(first.systemPrefix()).contains("MatrixCode", "产品");
        assertThat(first.estimatedStablePrefixTokens()).isGreaterThan(0);
    }

    @Test
    void 工具契约变化会改变稳定前缀哈希() {
        var builder = new PromptContractBuilder();

        var first = builder.build(ModelRole.TESTER, "matrixcode-local-tester", "tools-v1");
        var second = builder.build(ModelRole.TESTER, "matrixcode-local-tester", "tools-v2");

        assertThat(first.stablePrefixHash()).isNotEqualTo(second.stablePrefixHash());
    }

    @Test
    void 角色提示词变化不改变稳定前缀哈希且不增加稳定前缀Token() {
        var builder = new PromptContractBuilder();

        var base = builder.build(ModelRole.DEVELOPER, "deepseek-chat", "tools-v1", "先读代码再修改。");
        var changed = builder.build(ModelRole.DEVELOPER, "deepseek-chat", "tools-v1", "先读代码再修改。必须写测试。");

        assertThat(changed.systemPrefix()).contains("必须写测试");
        assertThat(changed.stablePrefixHash()).isEqualTo(base.stablePrefixHash());
        assertThat(changed.estimatedStablePrefixTokens()).isEqualTo(base.estimatedStablePrefixTokens());
    }

    @Test
    void 提示词契约输出稳定分区和动态槽位用于供应商缓存治理() {
        var builder = new PromptContractBuilder();

        var contract = builder.build(ModelRole.DEVELOPER, "deepseek-chat", "tools-v1", "先读代码再修改。");
        var changedRolePrompt = builder.build(ModelRole.DEVELOPER, "deepseek-chat", "tools-v1", "先读代码再修改。必须写测试。");

        assertThat(contract.promptPartitionPolicyId()).isEqualTo("deepseek-reasonix-partitions-v1");
        assertThat(contract.promptPartitionFingerprint()).isEqualTo(changedRolePrompt.promptPartitionFingerprint());
        assertThat(contract.stablePartitionCount()).isEqualTo(2);
        assertThat(contract.volatilePartitionCount()).isEqualTo(3);
        assertThat(contract.partitions()).extracting("key").containsExactly(
                "PLATFORM_STABLE_PREFIX",
                "TOOL_CONTRACT_VERSION",
                "ROLE_SYSTEM_PROMPT",
                "CONTEXT_MANIFEST_SLOT",
                "USER_INSTRUCTION_SLOT"
        );
        assertThat(contract.partitions()).filteredOn(partition -> partition.stability().equals("STABLE"))
                .allSatisfy(partition -> assertThat(partition.cacheable()).isTrue());
        assertThat(contract.partitions()).filteredOn(partition -> partition.stability().equals("VOLATILE"))
                .allSatisfy(partition -> assertThat(partition.cacheable()).isFalse());
    }

    @Test
    void 模型名称不污染稳定前缀哈希() {
        var builder = new PromptContractBuilder();

        var deepseek = builder.build(ModelRole.DEVELOPER, "deepseek-chat", "tools-v1", "先读代码再修改。");
        var qwen = builder.build(ModelRole.DEVELOPER, "qwen-plus", "tools-v1", "先读代码再修改。");

        assertThat(deepseek.stablePrefixHash()).isEqualTo(qwen.stablePrefixHash());
        assertThat(deepseek.systemPrefix()).doesNotContain("deepseek-chat");
        assertThat(qwen.systemPrefix()).doesNotContain("qwen-plus");
    }

    @Test
    void 同一角色会话第二次请求命中稳定前缀缓存() {
        var builder = new PromptContractBuilder();
        var estimator = new PromptCacheEstimator();
        var contract = builder.build(ModelRole.DEVELOPER, "matrixcode-local-developer", "tools-v1");

        var first = estimator.estimate("demo:DEVELOPER", contract, List.of("FROZEN_PRD", "CURRENT_EVENT"), "实现失败重试", "开发建议");
        var second = estimator.estimate("demo:DEVELOPER", contract, List.of("FROZEN_PRD", "CURRENT_EVENT"), "补充自测", "开发建议");

        assertThat(first.cacheHitTokens()).isZero();
        assertThat(first.cacheMissInputTokens()).isGreaterThan(contract.estimatedStablePrefixTokens());
        assertThat(second.cacheHitTokens()).isEqualTo(contract.estimatedStablePrefixTokens());
        assertThat(second.cacheMissInputTokens()).isLessThan(first.cacheMissInputTokens());
    }
}
