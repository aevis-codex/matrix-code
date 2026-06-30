package com.matrixcode.agent.application;

import com.matrixcode.agent.domain.ProductDraftRequest;
import com.matrixcode.agent.domain.ProductDraftResult;
import com.matrixcode.agent.domain.ProductDraftResult.DraftDocument;
import com.matrixcode.document.domain.DocumentType;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LocalProductDraftAgent {

    public ProductDraftResult generate(ProductDraftRequest request) {
        var requirement = request.requirement();
        return new ProductDraftResult(List.of(
                new DraftDocument(DocumentType.PRD, "产品需求草稿", prdContent(requirement)),
                new DraftDocument(DocumentType.ACCEPTANCE_CRITERIA, "验收标准草稿", acceptanceContent(requirement)),
                new DraftDocument(DocumentType.UI_BRIEF, "初始界面说明草稿", uiBriefContent(requirement))
        ));
    }

    private String prdContent(String requirement) {
        return """
                # 产品需求草稿

                原始产品输入：
                %s

                业务目标待确认：
                - 明确该需求希望改善的用户体验、业务效率或运营质量。
                - 识别核心使用对象、触发条件和期望结果。
                - 为产品、设计、研发和测试提供一致的草稿基线。

                范围边界：
                - 本草稿仅根据当前输入梳理初始需求，不替代正式评审结论。
                - 需要继续确认包含的用户场景、数据来源、规则口径和异常处理。
                - 暂不承诺未在输入中出现的业务流程、外部系统或运营动作。

                待确认问题：
                - 需求触发后由谁接收、处理或确认结果？
                - 成功、失败、超时和重复触发时分别如何处理？
                - 是否需要记录操作日志、通知历史或后续追踪信息？
                """.formatted(requirement);
    }

    private String acceptanceContent(String requirement) {
        return """
                # 验收标准草稿

                原始产品输入：
                %s

                验收关注点：
                - 用户在满足触发条件时可以看到或收到明确、可理解的结果。
                - 系统只在规则命中的情况下执行对应动作，并避免重复干扰。
                - 关键信息需要可追踪，便于后续排查和评估。
                - 重复生成同一输入时，草稿标题、类型和内容保持稳定。

                待确认问题：
                - 触发条件的阈值、时间窗口和数据刷新频率是什么？
                - 哪些角色可以查看、处理或关闭该事项？
                - 是否需要配置开关、权限控制或通知频率限制？
                """.formatted(requirement);
    }

    private String uiBriefContent(String requirement) {
        return """
                # 初始界面说明草稿

                原始产品输入：
                %s

                界面状态：
                - 初始状态展示需求相关的核心对象、当前状态和下一步动作。
                - 触发状态突出变化原因、影响范围和建议处理方式。
                - 空状态、加载状态和异常状态需要有明确提示，避免用户误判。

                待确认问题：
                - 该能力入口位于哪个页面、模块或工作台？
                - 用户完成主要动作后需要看到什么反馈？
                - 是否需要列表、详情、弹窗、通知或批量处理等交互形态？
                """.formatted(requirement);
    }
}
