package com.matrixcode.workbench.application;

import com.matrixcode.modelgateway.domain.ModelRequestRecord;
import com.matrixcode.realtime.domain.ProjectEvent;

import java.util.List;
import java.util.Map;

/**
 * 项目活动仓储接口。
 *
 * <p>作用域：模型请求和项目事件；场景：右侧关键事件、模型成本统计和实时协作回放。</p>
 */
public interface ProjectActivityRepository {

    /**
     * 读取按项目分组的模型请求记录。
     */
    Map<String, List<ModelRequestRecord>> loadModelRequests();

    /**
     * 保存按项目分组的模型请求记录。
     */
    void saveModelRequests(Map<String, List<ModelRequestRecord>> requests);

    /**
     * 读取按项目分组的项目事件。
     */
    Map<String, List<ProjectEvent>> loadProjectEvents();

    /**
     * 保存按项目分组的项目事件。
     */
    void saveProjectEvents(Map<String, List<ProjectEvent>> events);
}
