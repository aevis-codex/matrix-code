package com.matrixcode.persistence.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.approval.domain.AuditRecord;
import com.matrixcode.localexecution.application.LocalExecutionSnapshot;
import com.matrixcode.localexecution.application.LocalExecutionStateStore;
import com.matrixcode.localexecution.domain.ExecutionTask;
import com.matrixcode.localexecution.domain.LocalTaskLog;
import com.matrixcode.localexecution.domain.WorkspaceAuthorization;
import com.matrixcode.persistence.mybatis.entity.LocalAuditRecordEntity;
import com.matrixcode.persistence.mybatis.entity.LocalExecutionTaskEntity;
import com.matrixcode.persistence.mybatis.entity.LocalTaskLogEntity;
import com.matrixcode.persistence.mybatis.entity.LocalWorkspaceEntity;
import com.matrixcode.persistence.mybatis.entity.MatrixProjectEntity;
import com.matrixcode.persistence.mybatis.entity.MatrixUserEntity;
import com.matrixcode.persistence.mybatis.mapper.LocalAuditRecordMapper;
import com.matrixcode.persistence.mybatis.mapper.LocalExecutionTaskMapper;
import com.matrixcode.persistence.mybatis.mapper.LocalTaskLogMapper;
import com.matrixcode.persistence.mybatis.mapper.LocalWorkspaceMapper;
import com.matrixcode.persistence.mybatis.mapper.MatrixProjectMapper;
import com.matrixcode.persistence.mybatis.mapper.MatrixUserMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(prefix = "matrixcode.persistence", name = "mode", havingValue = "jdbc")
public class MybatisPlusLocalExecutionStateStore implements LocalExecutionStateStore {

    static final String SLICE_KEY = "local-execution";

    private final LocalWorkspaceMapper workspaceMapper;
    private final LocalExecutionTaskMapper taskMapper;
    private final LocalTaskLogMapper taskLogMapper;
    private final LocalAuditRecordMapper auditMapper;
    private final MatrixProjectMapper projectMapper;
    private final MatrixUserMapper userMapper;
    private final JdbcSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;
    private LocalExecutionSnapshot current;
    private boolean loaded;

    public MybatisPlusLocalExecutionStateStore(
            LocalWorkspaceMapper workspaceMapper,
            LocalExecutionTaskMapper taskMapper,
            LocalTaskLogMapper taskLogMapper,
            LocalAuditRecordMapper auditMapper,
            MatrixProjectMapper projectMapper,
            MatrixUserMapper userMapper,
            JdbcSnapshotRepository snapshotRepository,
            ObjectMapper objectMapper
    ) {
        this.workspaceMapper = workspaceMapper;
        this.taskMapper = taskMapper;
        this.taskLogMapper = taskLogMapper;
        this.auditMapper = auditMapper;
        this.projectMapper = projectMapper;
        this.userMapper = userMapper;
        this.snapshotRepository = snapshotRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 加载本地执行状态快照。
     *
     * <p>正式表已有数据时直接返回正式表状态；正式表为空时尝试读取旧
     * `local-execution` 快照并回填正式表，保障从文件/快照时代升级后仍能恢复历史数据。</p>
     */
    @Override
    @Transactional
    public synchronized LocalExecutionSnapshot load() {
        ensureLoaded();
        return current;
    }

    /**
     * 保存完整工作区授权列表。
     *
     * <p>工作区注册表以完整列表为状态源，因此这里保持旧仓储的整体替换语义。</p>
     */
    @Override
    @Transactional
    public synchronized void saveWorkspaces(List<WorkspaceAuthorization> workspaces) {
        ensureLoaded();
        current = new LocalExecutionSnapshot(1, workspaces, current.tasks(), current.taskLogs(), current.auditRecords());
        replaceWorkspaces(workspaces);
    }

    /**
     * 保存本地执行任务和任务日志。
     *
     * <p>任务与日志存在外键关系，写入时先清理日志再清理任务，重建时先写任务再写日志。</p>
     */
    @Override
    @Transactional
    public synchronized void saveTasks(
            Map<String, List<ExecutionTask>> tasks,
            Map<String, Map<String, List<LocalTaskLog>>> taskLogs
    ) {
        ensureLoaded();
        current = new LocalExecutionSnapshot(1, current.workspaces(), tasks, taskLogs, current.auditRecords());
        replaceTasks(tasks, taskLogs);
    }

    /**
     * 保存本地执行审批审计。
     *
     * <p>共享审计表还承载身份域记录，因此替换范围只限定为 `LOCAL_EXECUTION_TASK`。</p>
     */
    @Override
    @Transactional
    public synchronized void saveAuditRecords(List<AuditRecord> auditRecords) {
        ensureLoaded();
        current = new LocalExecutionSnapshot(1, current.workspaces(), current.tasks(), current.taskLogs(), auditRecords);
        replaceAuditRecords(auditRecords, current.tasks());
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        current = readSnapshot();
        loaded = true;
    }

    private LocalExecutionSnapshot readSnapshot() {
        var formal = readFormalSnapshot();
        if (hasData(formal)) {
            return formal;
        }
        var legacy = readLegacySnapshot();
        if (hasData(legacy)) {
            writeAll(legacy);
            return legacy;
        }
        return LocalExecutionSnapshot.empty();
    }

    private LocalExecutionSnapshot readFormalSnapshot() {
        return new LocalExecutionSnapshot(
                1,
                readWorkspaces(),
                readTasks(),
                readTaskLogs(),
                readAuditRecords()
        );
    }

    private List<WorkspaceAuthorization> readWorkspaces() {
        return workspaceMapper.selectList(new LambdaQueryWrapper<LocalWorkspaceEntity>()
                        .orderByAsc(LocalWorkspaceEntity::getCreatedAt)
                        .orderByAsc(LocalWorkspaceEntity::getId))
                .stream()
                .map(LocalWorkspaceEntity::toDomain)
                .toList();
    }

    private Map<String, List<ExecutionTask>> readTasks() {
        var grouped = new LinkedHashMap<String, List<ExecutionTask>>();
        taskMapper.selectList(new LambdaQueryWrapper<LocalExecutionTaskEntity>()
                        .orderByAsc(LocalExecutionTaskEntity::getProjectId)
                        .orderByAsc(LocalExecutionTaskEntity::getSortOrder)
                        .orderByDesc(LocalExecutionTaskEntity::getUpdatedAt)
                        .orderByDesc(LocalExecutionTaskEntity::getCreatedAt)
                        .orderByAsc(LocalExecutionTaskEntity::getId))
                .stream()
                .map(LocalExecutionTaskEntity::toDomain)
                .forEach(task -> grouped.computeIfAbsent(task.projectId(), ignored -> new ArrayList<>()).add(task));
        return copyListMap(grouped);
    }

    private Map<String, Map<String, List<LocalTaskLog>>> readTaskLogs() {
        var grouped = new LinkedHashMap<String, Map<String, List<LocalTaskLog>>>();
        taskLogMapper.selectList(new LambdaQueryWrapper<LocalTaskLogEntity>()
                        .orderByAsc(LocalTaskLogEntity::getProjectId)
                        .orderByAsc(LocalTaskLogEntity::getTaskId)
                        .orderByAsc(LocalTaskLogEntity::getSortOrder)
                        .orderByDesc(LocalTaskLogEntity::getCreatedAt)
                        .orderByAsc(LocalTaskLogEntity::getId))
                .stream()
                .map(LocalTaskLogEntity::toDomain)
                .forEach(log -> grouped
                        .computeIfAbsent(log.projectId(), ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(log.taskId(), ignored -> new ArrayList<>())
                        .add(log));
        return copyNestedListMap(grouped);
    }

    private List<AuditRecord> readAuditRecords() {
        return auditMapper.selectList(new LambdaQueryWrapper<LocalAuditRecordEntity>()
                        .eq(LocalAuditRecordEntity::getTargetType, LocalAuditRecordEntity.TARGET_TYPE)
                        .orderByAsc(LocalAuditRecordEntity::getSortOrder)
                        .orderByAsc(LocalAuditRecordEntity::getCreatedAt)
                        .orderByAsc(LocalAuditRecordEntity::getId))
                .stream()
                .map(LocalAuditRecordEntity::toDomain)
                .toList();
    }

    private LocalExecutionSnapshot readLegacySnapshot() {
        return snapshotRepository.load(SLICE_KEY)
                .filter(snapshot -> snapshot.version() == 1)
                .map(snapshot -> read(snapshot.payload()))
                .orElseGet(LocalExecutionSnapshot::empty);
    }

    private LocalExecutionSnapshot read(String payload) {
        try {
            var snapshot = objectMapper.readValue(payload, LocalExecutionSnapshot.class);
            if (snapshot.version() != 1) {
                return LocalExecutionSnapshot.empty();
            }
            return snapshot;
        } catch (JsonProcessingException | RuntimeException ignored) {
            return LocalExecutionSnapshot.empty();
        }
    }

    private void writeAll(LocalExecutionSnapshot snapshot) {
        replaceWorkspaces(snapshot.workspaces());
        replaceTasks(snapshot.tasks(), snapshot.taskLogs());
        replaceAuditRecords(snapshot.auditRecords(), snapshot.tasks());
    }

    private void replaceWorkspaces(List<WorkspaceAuthorization> workspaces) {
        workspaceMapper.delete(new LambdaQueryWrapper<LocalWorkspaceEntity>()
                .isNotNull(LocalWorkspaceEntity::getId));
        if (workspaces == null || workspaces.isEmpty()) {
            return;
        }
        var now = Instant.now();
        for (var workspace : workspaces) {
            ensureProject(workspace.projectId(), "本地执行与审批审计", now);
            workspaceMapper.insert(LocalWorkspaceEntity.fromDomain(workspace, now));
        }
    }

    private void replaceTasks(
            Map<String, List<ExecutionTask>> tasks,
            Map<String, Map<String, List<LocalTaskLog>>> taskLogs
    ) {
        taskLogMapper.delete(new LambdaQueryWrapper<LocalTaskLogEntity>()
                .isNotNull(LocalTaskLogEntity::getId));
        taskMapper.delete(new LambdaQueryWrapper<LocalExecutionTaskEntity>()
                .isNotNull(LocalExecutionTaskEntity::getId));
        var now = Instant.now();
        if (tasks != null) {
            for (var entry : tasks.entrySet()) {
                ensureProject(entry.getKey(), "本地执行与审批审计", now);
                var index = 0;
                for (var task : entry.getValue()) {
                    taskMapper.insert(LocalExecutionTaskEntity.fromDomain(task, index++, now));
                }
            }
        }
        if (taskLogs == null) {
            return;
        }
        for (var projectLogs : taskLogs.entrySet()) {
            ensureProject(projectLogs.getKey(), "本地执行与审批审计", now);
            for (var taskEntry : projectLogs.getValue().entrySet()) {
                var index = 0;
                for (var log : taskEntry.getValue()) {
                    taskLogMapper.insert(LocalTaskLogEntity.fromDomain(log, index++, now));
                }
            }
        }
    }

    private void replaceAuditRecords(List<AuditRecord> auditRecords, Map<String, List<ExecutionTask>> tasks) {
        auditMapper.delete(new LambdaQueryWrapper<LocalAuditRecordEntity>()
                .eq(LocalAuditRecordEntity::getTargetType, LocalAuditRecordEntity.TARGET_TYPE));
        if (auditRecords == null || auditRecords.isEmpty()) {
            return;
        }
        var now = Instant.now();
        var index = 0;
        for (var record : auditRecords) {
            var projectId = projectIdForAudit(record, tasks);
            ensureProject(projectId, "本地执行与审批审计", now);
            ensureUser(record.actorId(), now);
            auditMapper.insert(LocalAuditRecordEntity.fromDomain(record, projectId, index++, now));
        }
    }

    private String projectIdForAudit(AuditRecord record, Map<String, List<ExecutionTask>> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return "local-execution";
        }
        return tasks.entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(task -> task.taskId().equals(record.taskId())))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse("local-execution");
    }

    private void ensureProject(String projectId, String stage, Instant now) {
        if (projectMapper.updateById(MatrixProjectEntity.touch(projectId, now)) > 0) {
            return;
        }
        var project = MatrixProjectEntity.fallbackProject(projectId, now);
        project.setCurrentStage(stage);
        projectMapper.insert(project);
    }

    private void ensureUser(String userId, Instant now) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        var normalized = userId.trim();
        if (userMapper.updateById(MatrixUserEntity.touch(normalized, now)) == 0) {
            userMapper.insert(MatrixUserEntity.fallbackUser(normalized, now));
        }
    }

    private boolean hasData(LocalExecutionSnapshot snapshot) {
        return !snapshot.workspaces().isEmpty()
                || !snapshot.tasks().isEmpty()
                || !snapshot.taskLogs().isEmpty()
                || !snapshot.auditRecords().isEmpty();
    }

    private Map<String, List<ExecutionTask>> copyListMap(Map<String, List<ExecutionTask>> source) {
        var copy = new HashMap<String, List<ExecutionTask>>();
        source.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Map.copyOf(copy);
    }

    private Map<String, Map<String, List<LocalTaskLog>>> copyNestedListMap(
            Map<String, Map<String, List<LocalTaskLog>>> source
    ) {
        var copy = new HashMap<String, Map<String, List<LocalTaskLog>>>();
        source.forEach((projectId, logsByTask) -> {
            var nested = new HashMap<String, List<LocalTaskLog>>();
            logsByTask.forEach((taskId, logs) -> nested.put(taskId, List.copyOf(logs)));
            copy.put(projectId, Map.copyOf(nested));
        });
        return Map.copyOf(copy);
    }
}
