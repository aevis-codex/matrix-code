package com.matrixcode.bug.application;

import com.matrixcode.bug.domain.ProjectBug;

import java.util.List;

/**
 * 项目 Bug 仓储接口。
 *
 * <p>作用域：测试和验收缺陷域；场景：保存、恢复和查询项目缺陷流转状态。</p>
 */
public interface BugRepository {

    /**
     * 读取所有项目 Bug。
     */
    List<ProjectBug> load();

    /**
     * 保存当前 Bug 集合快照。
     */
    void save(List<ProjectBug> bugs);
}
