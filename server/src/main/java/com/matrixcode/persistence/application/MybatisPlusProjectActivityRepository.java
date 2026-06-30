package com.matrixcode.persistence.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.modelgateway.domain.ModelRequestRecord;
import com.matrixcode.persistence.mybatis.entity.MatrixProjectEntity;
import com.matrixcode.persistence.mybatis.entity.MatrixUserEntity;
import com.matrixcode.persistence.mybatis.entity.ModelRequestEntity;
import com.matrixcode.persistence.mybatis.entity.ProjectEventEntity;
import com.matrixcode.persistence.mybatis.mapper.MatrixProjectMapper;
import com.matrixcode.persistence.mybatis.mapper.MatrixUserMapper;
import com.matrixcode.persistence.mybatis.mapper.ModelRequestMapper;
import com.matrixcode.persistence.mybatis.mapper.ProjectEventMapper;
import com.matrixcode.realtime.domain.ProjectEvent;
import com.matrixcode.workbench.application.ProjectActivityRepository;
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
public class MybatisPlusProjectActivityRepository implements ProjectActivityRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final ModelRequestMapper modelRequestMapper;
    private final ProjectEventMapper projectEventMapper;
    private final MatrixProjectMapper projectMapper;
    private final MatrixUserMapper userMapper;
    private final ObjectMapper objectMapper;

    public MybatisPlusProjectActivityRepository(
            ModelRequestMapper modelRequestMapper,
            ProjectEventMapper projectEventMapper,
            MatrixProjectMapper projectMapper,
            MatrixUserMapper userMapper,
            ObjectMapper objectMapper
    ) {
        this.modelRequestMapper = modelRequestMapper;
        this.projectEventMapper = projectEventMapper;
        this.projectMapper = projectMapper;
        this.userMapper = userMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 读取所有项目的模型请求记录。
     *
     * <p>排序规则保持旧 JDBC 仓储行为：项目、创建时间、ID 稳定排序，保证模型网关
     * 最近请求列表在仓储实现替换后顺序不变。</p>
     */
    @Override
    public Map<String, List<ModelRequestRecord>> loadModelRequests() {
        var grouped = new LinkedHashMap<String, List<ModelRequestRecord>>();
        modelRequestMapper.selectList(new LambdaQueryWrapper<ModelRequestEntity>()
                        .orderByAsc(ModelRequestEntity::getProjectId)
                        .orderByAsc(ModelRequestEntity::getCreatedAt)
                        .orderByAsc(ModelRequestEntity::getId))
                .forEach(entity -> {
                    var record = entity.toDomain(contextTypes(entity.getContextTypes()));
                    grouped.computeIfAbsent(record.projectId(), ignored -> new ArrayList<>()).add(record);
                });
        return immutableGrouped(grouped);
    }

    /**
     * 按项目替换模型请求快照。
     *
     * <p>模型网关保存的是当前项目完整最近请求集合；因此这里延续旧 JDBC 语义，按项目删除后再插入。
     * 写入前会补齐项目外键，并为非空操作者补齐用户外键。</p>
     */
    @Override
    @Transactional
    public void saveModelRequests(Map<String, List<ModelRequestRecord>> requests) {
        if (requests == null || requests.isEmpty()) {
            return;
        }
        for (var entry : requests.entrySet()) {
            ensureProject(entry.getKey(), "模型请求", timestampFor(entry.getValue()));
            modelRequestMapper.delete(new LambdaQueryWrapper<ModelRequestEntity>()
                    .eq(ModelRequestEntity::getProjectId, entry.getKey()));
            for (var record : entry.getValue()) {
                ensureUser(record.actorUserId(), record.createdAt());
                modelRequestMapper.insert(ModelRequestEntity.fromDomain(record, contextTypesJson(record.contextTypes())));
            }
        }
    }

    /**
     * 读取所有项目事件。
     *
     * <p>项目事件用于 SSE 最近事件、右侧关键事件和运行审计入口，读取顺序必须与旧仓储一致。</p>
     */
    @Override
    public Map<String, List<ProjectEvent>> loadProjectEvents() {
        var grouped = new LinkedHashMap<String, List<ProjectEvent>>();
        projectEventMapper.selectList(new LambdaQueryWrapper<ProjectEventEntity>()
                        .orderByAsc(ProjectEventEntity::getProjectId)
                        .orderByAsc(ProjectEventEntity::getCreatedAt)
                        .orderByAsc(ProjectEventEntity::getId))
                .forEach(entity -> {
                    var event = entity.toDomain();
                    grouped.computeIfAbsent(event.projectId(), ignored -> new ArrayList<>()).add(event);
                });
        return immutableGrouped(grouped);
    }

    /**
     * 按项目替换项目事件快照。
     *
     * <p>事件总线保存的是当前内存窗口快照，不是单条事件追加日志；这里保持项目级替换语义，
     * 避免重启回填或内存窗口刷新后产生重复事件。</p>
     */
    @Override
    @Transactional
    public void saveProjectEvents(Map<String, List<ProjectEvent>> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        for (var entry : events.entrySet()) {
            ensureProject(entry.getKey(), "项目事件", timestampForEvents(entry.getValue()));
            projectEventMapper.delete(new LambdaQueryWrapper<ProjectEventEntity>()
                    .eq(ProjectEventEntity::getProjectId, entry.getKey()));
            for (var event : entry.getValue()) {
                projectEventMapper.insert(ProjectEventEntity.fromDomain(event));
            }
        }
    }

    private void ensureProject(String projectId, String stage, Instant now) {
        var timestamp = now == null ? Instant.now() : now;
        if (projectMapper.updateById(MatrixProjectEntity.touch(projectId, timestamp)) == 0) {
            var project = MatrixProjectEntity.fallbackProject(projectId, timestamp);
            project.setCurrentStage(stage);
            projectMapper.insert(project);
        }
    }

    private void ensureUser(String userId, Instant now) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        var normalized = userId.trim();
        var timestamp = now == null ? Instant.now() : now;
        if (userMapper.updateById(MatrixUserEntity.touch(normalized, timestamp)) == 0) {
            userMapper.insert(MatrixUserEntity.fallbackUser(normalized, timestamp));
        }
    }

    private Instant timestampFor(List<ModelRequestRecord> records) {
        if (records == null || records.isEmpty()) {
            return Instant.now();
        }
        return records.getLast().createdAt();
    }

    private Instant timestampForEvents(List<ProjectEvent> events) {
        if (events == null || events.isEmpty()) {
            return Instant.now();
        }
        return events.getLast().occurredAt();
    }

    private List<String> contextTypes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("模型请求上下文类型解析失败：" + exception.getMessage(), exception);
        }
    }

    private String contextTypesJson(List<String> contextTypes) {
        try {
            return objectMapper.writeValueAsString(contextTypes == null ? List.of() : contextTypes);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("模型请求上下文类型序列化失败：" + exception.getMessage(), exception);
        }
    }

    private <T> Map<String, List<T>> immutableGrouped(Map<String, List<T>> grouped) {
        var copy = new LinkedHashMap<String, List<T>>();
        grouped.forEach((projectId, records) -> copy.put(projectId, List.copyOf(records)));
        return Map.copyOf(copy);
    }
}
