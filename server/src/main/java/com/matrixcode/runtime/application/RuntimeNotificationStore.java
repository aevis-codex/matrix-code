package com.matrixcode.runtime.application;

/**
 * 运行态提醒存储接口。
 *
 * <p>作用域：运行提醒应用层；场景：工作台重启恢复审批、任务和 Compose 操作提醒。</p>
 */
public interface RuntimeNotificationStore {

    /**
     * 读取完整提醒快照。
     */
    RuntimeNotificationSnapshot load();

    /**
     * 保存完整提醒快照。
     */
    void save(RuntimeNotificationSnapshot snapshot);
}
