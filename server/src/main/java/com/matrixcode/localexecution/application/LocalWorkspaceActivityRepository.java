package com.matrixcode.localexecution.application;

import com.matrixcode.localexecution.domain.FileOperationRecord;
import com.matrixcode.localexecution.domain.GitDiffSummary;

import java.util.List;
import java.util.Map;

/**
 * 本地工作区活动仓储接口。
 *
 * <p>作用域：应用层持久化边界。主要场景是记录本地文件读写、命令执行后的 Git Diff 摘要，
 * 供开发智能体交付、人工复盘和运行指标面板读取。实现类负责把内存窗口保存到正式存储。</p>
 */
public interface LocalWorkspaceActivityRepository {

    /**
     * 读取所有项目最近的文件操作记录。
     *
     * <p>返回结构按项目分组，每个项目内保持“最新操作在前”的顺序，用于工作台重启恢复
     * 和右侧运行指标展示。</p>
     */
    Map<String, List<FileOperationRecord>> loadFileOperations();

    /**
     * 保存所有项目最近的文件操作窗口。
     *
     * <p>调用方传入的是当前内存窗口快照，仓储需要保持整体替换语义，避免重启回填或重复
     * 保存后产生重复记录。</p>
     */
    void saveFileOperations(Map<String, List<FileOperationRecord>> operations);

    /**
     * 读取所有项目最近一次 Git Diff 摘要。
     *
     * <p>每个项目只保留最新摘要，用于开发智能体交付前回看代码变更范围。</p>
     */
    Map<String, GitDiffSummary> loadGitDiffSummaries();

    /**
     * 保存所有项目最近一次 Git Diff 摘要。
     *
     * <p>调用方传入的是内存中最新快照，仓储需要覆盖同一项目旧摘要。</p>
     */
    void saveGitDiffSummaries(Map<String, GitDiffSummary> summaries);
}
