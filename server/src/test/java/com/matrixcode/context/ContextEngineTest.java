package com.matrixcode.context;

import com.matrixcode.context.application.ContextEngine;
import com.matrixcode.context.domain.ContextBlock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextEngineTest {

    @Test
    void 开发角色只接收冻结文档和当前状态事件() {
        var engine = new ContextEngine();
        var manifest = engine.build("DEVELOPMENT", List.of(
                new ContextBlock("PRODUCT_CHAT", "产品长对话", false),
                new ContextBlock("FROZEN_PRD", "PRD v1", true),
                new ContextBlock("CURRENT_EVENT", "任务进入开发", true)
        ));

        assertThat(manifest.role()).isEqualTo("DEVELOPMENT");
        assertThat(manifest.blocks()).extracting(ContextBlock::type)
                .containsExactly("FROZEN_PRD", "CURRENT_EVENT");
        assertThat(manifest.omittedTypes()).containsExactly("PRODUCT_CHAT");
    }

    @Test
    void 空候选列表返回空清单() {
        var engine = new ContextEngine();

        var manifest = engine.build("DEVELOPMENT", List.of());

        assertThat(manifest.role()).isEqualTo("DEVELOPMENT");
        assertThat(manifest.blocks()).isEmpty();
        assertThat(manifest.omittedTypes()).isEmpty();
    }

    @Test
    void 清单不受候选列表后续修改影响且列表不可变() {
        var engine = new ContextEngine();
        var candidates = new ArrayList<>(List.of(
                new ContextBlock("FROZEN_PRD", "PRD v1", true),
                new ContextBlock("PRODUCT_CHAT", "产品长对话", false)
        ));

        var manifest = engine.build("DEVELOPMENT", candidates);
        candidates.add(new ContextBlock("CURRENT_EVENT", "任务进入开发", true));

        assertThat(manifest.blocks()).extracting(ContextBlock::type)
                .containsExactly("FROZEN_PRD");
        assertThat(manifest.omittedTypes()).containsExactly("PRODUCT_CHAT");
        assertThatThrownBy(() -> manifest.blocks().add(new ContextBlock("CURRENT_EVENT", "任务进入开发", true)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> manifest.omittedTypes().add("PRODUCT_CHAT"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 空角色会被拒绝避免统计维度缺失() {
        var engine = new ContextEngine();
        var candidates = List.of(new ContextBlock("FROZEN_PRD", "PRD v1", true));

        assertThatThrownBy(() -> engine.build(null, candidates))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("角色不能为空");
        assertThatThrownBy(() -> engine.build("   ", candidates))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("角色不能为空");
    }

    @Test
    void 空候选列表引用会被拒绝避免清单来源不明() {
        var engine = new ContextEngine();

        assertThatThrownBy(() -> engine.build("DEVELOPMENT", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("候选上下文不能为空");
    }

    @Test
    void 空上下文类型或摘要会被拒绝避免清单污染() {
        assertThatThrownBy(() -> new ContextBlock(null, "PRD v1", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("上下文类型不能为空");
        assertThatThrownBy(() -> new ContextBlock("   ", "PRD v1", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("上下文类型不能为空");
        assertThatThrownBy(() -> new ContextBlock("FROZEN_PRD", null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("上下文摘要不能为空");
        assertThatThrownBy(() -> new ContextBlock("FROZEN_PRD", "   ", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("上下文摘要不能为空");
    }
}
