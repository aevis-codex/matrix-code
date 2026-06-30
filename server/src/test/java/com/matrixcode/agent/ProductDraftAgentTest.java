package com.matrixcode.agent;

import com.matrixcode.agent.application.LocalProductDraftAgent;
import com.matrixcode.agent.domain.ProductDraftRequest;
import com.matrixcode.document.domain.DocumentType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductDraftAgentTest {

    private final LocalProductDraftAgent agent = new LocalProductDraftAgent();

    @Test
    void 根据产品输入生成稳定的三份中文草稿() {
        var result = agent.generate(new ProductDraftRequest(
                "支付失败后允许用户重新发起支付，并展示失败原因、订单状态和客服入口。"
        ));

        assertThat(result.documents()).hasSize(3);
        assertThat(result.documents()).extracting("type")
                .containsExactly(DocumentType.PRD, DocumentType.ACCEPTANCE_CRITERIA, DocumentType.UI_BRIEF);
        assertThat(result.documents().getFirst().title()).isEqualTo("产品需求草稿");
        assertThat(result.documents().getFirst().content())
                .contains("草稿")
                .contains("支付失败后允许用户重新发起支付")
                .contains("业务目标")
                .contains("范围边界");
        assertThat(agent.generate(new ProductDraftRequest("支付失败后允许用户重新发起支付，并展示失败原因、订单状态和客服入口。")))
                .isEqualTo(result);
    }

    @Test
    void 非支付输入不会混入支付失败场景结论() {
        var requirement = "库存低于安全水位时提醒仓库管理员补货。";

        var result = agent.generate(new ProductDraftRequest(requirement));
        var combinedContent = result.documents().stream()
                .map(document -> document.title() + "\n" + document.content())
                .reduce("", String::concat);

        assertThat(result.documents()).hasSize(3);
        assertThat(result.documents()).extracting("title")
                .containsExactly("产品需求草稿", "验收标准草稿", "初始界面说明草稿");
        assertThat(combinedContent)
                .contains(requirement)
                .doesNotContain("支付系统")
                .doesNotContain("支付失败")
                .doesNotContain("重新支付")
                .doesNotContain("客服入口")
                .doesNotContain("订单状态")
                .doesNotContain("支付失败页");
    }

    @Test
    void 空产品输入会被拒绝() {
        assertThatThrownBy(() -> agent.generate(new ProductDraftRequest(" ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("产品需求不能为空");
    }
}
