package com.matrixcode.usage;

import com.matrixcode.usage.application.UsageCalculator;
import com.matrixcode.usage.domain.ModelPrice;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsageCalculatorTest {

    @Test
    void 缓存命中词元按更低单价计费() {
        var calculator = new UsageCalculator();
        var price = new ModelPrice("deepseek-v4-pro", "CNY", 0.025, 3.0, 6.0);

        var record = calculator.calculate("role-dev", price, 80_000, 20_000, 10_000);

        assertThat(record.roleSessionId()).isEqualTo("role-dev");
        assertThat(record.model()).isEqualTo("deepseek-v4-pro");
        assertThat(record.cacheHitTokens()).isEqualTo(80_000);
        assertThat(record.cacheMissInputTokens()).isEqualTo(20_000);
        assertThat(record.outputTokens()).isEqualTo(10_000);
        assertThat(record.cacheHitRate()).isEqualTo(0.80);
        assertThat(record.estimatedCost()).isEqualTo(0.122);
        assertThat(record.currency()).isEqualTo("CNY");
    }

    @Test
    void 提示词元为零时缓存命中率为零() {
        var calculator = new UsageCalculator();
        var price = new ModelPrice("deepseek-v4-pro", "CNY", 0.025, 3.0, 6.0);

        var record = calculator.calculate("role-dev", price, 0, 0, 10_000);

        assertThat(record.cacheHitRate()).isEqualTo(0.0);
        assertThat(record.estimatedCost()).isEqualTo(0.060);
    }

    @Test
    void 负词元数会被拒绝避免污染费用统计() {
        var calculator = new UsageCalculator();
        var price = new ModelPrice("deepseek-v4-pro", "CNY", 0.025, 3.0, 6.0);

        assertThatThrownBy(() -> calculator.calculate("role-dev", price, -1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("词元数不能为负数");
    }

    @Test
    void 空角色会话ID会被拒绝避免聚合维度缺失() {
        var calculator = new UsageCalculator();
        var price = new ModelPrice("deepseek-v4-pro", "CNY", 0.025, 3.0, 6.0);

        assertThatThrownBy(() -> calculator.calculate(null, price, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("角色会话 ID 不能为空");
        assertThatThrownBy(() -> calculator.calculate("   ", price, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("角色会话 ID 不能为空");
    }

    @Test
    void 空模型名称会被拒绝避免聚合维度缺失() {
        assertThatThrownBy(() -> new ModelPrice(null, "CNY", 0.025, 3.0, 6.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("模型名称不能为空");
        assertThatThrownBy(() -> new ModelPrice("   ", "CNY", 0.025, 3.0, 6.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("模型名称不能为空");
    }

    @Test
    void 空币种会被拒绝避免聚合维度缺失() {
        assertThatThrownBy(() -> new ModelPrice("deepseek-v4-pro", null, 0.025, 3.0, 6.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("币种不能为空");
        assertThatThrownBy(() -> new ModelPrice("deepseek-v4-pro", "   ", 0.025, 3.0, 6.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("币种不能为空");
    }

    @Test
    void 模型价格不接受负单价() {
        assertThatThrownBy(() -> new ModelPrice("deepseek-v4-pro", "CNY", -0.001, 3.0, 6.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("模型单价不能为负数");
    }

    @Test
    void 模型价格不接受非有限单价() {
        assertThatThrownBy(() -> new ModelPrice("deepseek-v4-pro", "CNY", Double.NaN, 3.0, 6.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("模型单价必须是有限数字");
        assertThatThrownBy(() -> new ModelPrice("deepseek-v4-pro", "CNY", 0.025, Double.POSITIVE_INFINITY, 6.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("模型单价必须是有限数字");
    }
}
