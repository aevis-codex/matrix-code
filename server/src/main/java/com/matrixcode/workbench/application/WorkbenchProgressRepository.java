package com.matrixcode.workbench.application;

import com.matrixcode.workflow.domain.WorkflowEvent;
import com.matrixcode.workflow.domain.WorkflowItem;

import java.util.List;
import java.util.Map;

/**
 * 工作流进度仓储接口。
 *
 * <p>作用域：项目工作台阶段流转；场景：恢复阶段卡片、事件时间线和产品验收状态。</p>
 */
public interface WorkbenchProgressRepository {

    /**
     * 读取工作流阶段项。
     */
    List<WorkflowItem> loadWorkflowItems();

    /**
     * 保存工作流阶段项。
     */
    void saveWorkflowItems(List<WorkflowItem> items);

    /**
     * 读取按项目分组的工作流事件。
     */
    Map<String, List<WorkflowEvent>> loadWorkflowEvents();

    /**
     * 保存按项目分组的工作流事件。
     */
    void saveWorkflowEvents(Map<String, List<WorkflowEvent>> events);

    /**
     * 读取产品验收状态。
     */
    Map<String, WorkbenchStateSnapshot.AcceptanceState> loadAcceptances();

    /**
     * 保存产品验收状态。
     */
    void saveAcceptances(Map<String, WorkbenchStateSnapshot.AcceptanceState> acceptances);
}
