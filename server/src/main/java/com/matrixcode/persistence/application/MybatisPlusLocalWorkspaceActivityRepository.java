package com.matrixcode.persistence.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.localexecution.application.LocalWorkspaceActivityRepository;
import com.matrixcode.localexecution.domain.FileOperationRecord;
import com.matrixcode.localexecution.domain.GitDiffSummary;
import com.matrixcode.persistence.mybatis.entity.LocalFileOperationEntity;
import com.matrixcode.persistence.mybatis.entity.LocalGitDiffSummaryEntity;
import com.matrixcode.persistence.mybatis.entity.MatrixProjectEntity;
import com.matrixcode.persistence.mybatis.mapper.LocalFileOperationMapper;
import com.matrixcode.persistence.mybatis.mapper.LocalGitDiffSummaryMapper;
import com.matrixcode.persistence.mybatis.mapper.MatrixProjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
@ConditionalOnProperty(prefix = "matrixcode.persistence", name = "mode", havingValue = "jdbc")
public class MybatisPlusLocalWorkspaceActivityRepository implements LocalWorkspaceActivityRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final LocalFileOperationMapper fileOperationMapper;
    private final LocalGitDiffSummaryMapper gitDiffSummaryMapper;
    private final MatrixProjectMapper projectMapper;
    private final ObjectMapper objectMapper;

    public MybatisPlusLocalWorkspaceActivityRepository(
            LocalFileOperationMapper fileOperationMapper,
            LocalGitDiffSummaryMapper gitDiffSummaryMapper,
            MatrixProjectMapper projectMapper,
            ObjectMapper objectMapper
    ) {
        this.fileOperationMapper = fileOperationMapper;
        this.gitDiffSummaryMapper = gitDiffSummaryMapper;
        this.projectMapper = projectMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 从正式表读取所有项目最近文件操作。
     *
     * <p>排序按项目、窗口顺序、发生时间、ID 固定，保证工作台重启后的展示顺序稳定。</p>
     */
    @Override
    public Map<String, List<FileOperationRecord>> loadFileOperations() {
        var grouped = new LinkedHashMap<String, List<FileOperationRecord>>();
        fileOperationMapper.selectList(new LambdaQueryWrapper<LocalFileOperationEntity>()
                        .orderByAsc(LocalFileOperationEntity::getProjectId)
                        .orderByAsc(LocalFileOperationEntity::getSortOrder)
                        .orderByDesc(LocalFileOperationEntity::getCreatedAt)
                        .orderByAsc(LocalFileOperationEntity::getId))
                .stream()
                .map(LocalFileOperationEntity::toDomain)
                .forEach(record -> grouped.computeIfAbsent(record.projectId(), ignored -> new ArrayList<>()).add(record));
        return immutableGrouped(grouped);
    }

    /**
     * 按项目替换最近文件操作窗口。
     *
     * <p>调用方保存的是当前内存窗口快照，因此这里先删除项目旧窗口，再按传入顺序写入最新窗口。</p>
     */
    @Override
    @Transactional
    public void saveFileOperations(Map<String, List<FileOperationRecord>> operations) {
        if (operations == null || operations.isEmpty()) {
            return;
        }
        var now = Instant.now();
        for (var entry : operations.entrySet()) {
            ensureProject(entry.getKey(), "本地工作区活动", now);
            fileOperationMapper.delete(new LambdaQueryWrapper<LocalFileOperationEntity>()
                    .eq(LocalFileOperationEntity::getProjectId, entry.getKey()));
            var index = 0;
            for (var record : entry.getValue()) {
                fileOperationMapper.insert(LocalFileOperationEntity.fromDomain(record, index++, now));
            }
        }
    }

    /**
     * 从正式表读取每个项目最近一次 Git Diff 摘要。
     */
    @Override
    public Map<String, GitDiffSummary> loadGitDiffSummaries() {
        var summaries = new LinkedHashMap<String, GitDiffSummary>();
        gitDiffSummaryMapper.selectList(new LambdaQueryWrapper<LocalGitDiffSummaryEntity>()
                        .orderByAsc(LocalGitDiffSummaryEntity::getProjectId))
                .forEach(entity -> summaries.put(entity.getProjectId(),
                        entity.toDomain(changedFiles(entity.getChangedFilesJson()))));
        return Map.copyOf(summaries);
    }

    /**
     * 覆盖保存每个项目最近一次 Git Diff 摘要。
     *
     * <p>Git Diff 摘要是“每项目一条”的最新状态，使用 `project_id` 唯一键做幂等覆盖。</p>
     */
    @Override
    @Transactional
    public void saveGitDiffSummaries(Map<String, GitDiffSummary> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return;
        }
        var now = Instant.now();
        for (var entry : summaries.entrySet()) {
            ensureProject(entry.getKey(), "本地工作区活动", now);
            var entity = LocalGitDiffSummaryEntity.fromDomain(
                    entry.getValue(),
                    changedFilesJson(entry.getValue().changedFiles()),
                    now
            );
            if (gitDiffSummaryMapper.update(entity, new LambdaUpdateWrapper<LocalGitDiffSummaryEntity>()
                    .eq(LocalGitDiffSummaryEntity::getProjectId, entry.getKey())) == 0) {
                gitDiffSummaryMapper.insert(entity);
            }
        }
    }

    private void ensureProject(String projectId, String stage, Instant now) {
        if (projectMapper.updateById(MatrixProjectEntity.touch(projectId, now)) == 0) {
            var project = MatrixProjectEntity.fallbackProject(projectId, now);
            project.setCurrentStage(stage);
            projectMapper.insert(project);
        }
    }

    private String changedFilesJson(List<String> changedFiles) {
        try {
            return objectMapper.writeValueAsString(changedFiles == null ? List.of() : changedFiles);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Git Diff 变更文件列表无法序列化", exception);
        }
    }

    private List<String> changedFiles(String changedFilesJson) {
        if (changedFilesJson == null || changedFilesJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(changedFilesJson, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Git Diff 变更文件列表无法反序列化", exception);
        }
    }

    private <T> Map<String, List<T>> immutableGrouped(Map<String, List<T>> grouped) {
        var copy = new LinkedHashMap<String, List<T>>();
        grouped.forEach((key, records) -> copy.put(key, List.copyOf(records)));
        return Map.copyOf(copy);
    }
}
