package com.matrixcode.persistence.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.persistence.mybatis.entity.MatrixProjectEntity;
import com.matrixcode.persistence.mybatis.entity.MatrixUserEntity;
import com.matrixcode.persistence.mybatis.entity.RuntimeNotificationEntity;
import com.matrixcode.persistence.mybatis.entity.RuntimeNotificationReadEntity;
import com.matrixcode.persistence.mybatis.mapper.MatrixProjectMapper;
import com.matrixcode.persistence.mybatis.mapper.MatrixUserMapper;
import com.matrixcode.persistence.mybatis.mapper.RuntimeNotificationMapper;
import com.matrixcode.persistence.mybatis.mapper.RuntimeNotificationReadMapper;
import com.matrixcode.runtime.application.RuntimeNotificationSnapshot;
import com.matrixcode.runtime.application.RuntimeNotificationStore;
import com.matrixcode.runtime.domain.RuntimeNotification;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@ConditionalOnProperty(prefix = "matrixcode.persistence", name = "mode", havingValue = "jdbc")
public class MybatisPlusRuntimeNotificationStore implements RuntimeNotificationStore {

    static final String SLICE_KEY = "runtime-notifications";

    private final RuntimeNotificationMapper notificationMapper;
    private final RuntimeNotificationReadMapper readMapper;
    private final MatrixProjectMapper projectMapper;
    private final MatrixUserMapper userMapper;
    private final JdbcSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;

    public MybatisPlusRuntimeNotificationStore(
            RuntimeNotificationMapper notificationMapper,
            RuntimeNotificationReadMapper readMapper,
            MatrixProjectMapper projectMapper,
            MatrixUserMapper userMapper,
            JdbcSnapshotRepository snapshotRepository,
            ObjectMapper objectMapper
    ) {
        this.notificationMapper = notificationMapper;
        this.readMapper = readMapper;
        this.projectMapper = projectMapper;
        this.userMapper = userMapper;
        this.snapshotRepository = snapshotRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 从正式运行态提醒表读取快照。
     *
     * <p>正式表为空时会读取旧 `runtime-notifications` 快照并回填正式表，确保第 14 阶段以来的
     * 轻量持久化数据在升级到 MyBatis-Plus 仓储后仍能被恢复。</p>
     */
    @Override
    public RuntimeNotificationSnapshot load() {
        var formal = readFormalSnapshot();
        if (!formal.projects().isEmpty()) {
            return formal;
        }
        var legacy = readLegacySnapshot();
        if (!legacy.projects().isEmpty()) {
            save(legacy);
            return legacy;
        }
        return RuntimeNotificationSnapshot.empty();
    }

    /**
     * 保存完整运行态提醒快照。
     *
     * <p>运行态提醒服务当前以项目快照为写入单位，因此这里保持“先清空、再重建”的原有语义，
     * 避免历史提醒删除后仍残留在正式表中。</p>
     */
    @Override
    @Transactional
    public void save(RuntimeNotificationSnapshot snapshot) {
        readMapper.delete(new LambdaQueryWrapper<RuntimeNotificationReadEntity>()
                .isNotNull(RuntimeNotificationReadEntity::getId));
        notificationMapper.delete(new LambdaQueryWrapper<RuntimeNotificationEntity>()
                .isNotNull(RuntimeNotificationEntity::getId));
        if (snapshot == null || snapshot.projects().isEmpty()) {
            return;
        }
        var notificationIds = new HashMap<String, Set<String>>();
        for (var notifications : snapshot.projects().values()) {
            for (var notification : notifications) {
                ensureProject(notification.projectId(), notification.occurredAt());
                ensureUser(notification.readByUserId(), notification.readAt());
                notificationMapper.insert(RuntimeNotificationEntity.fromDomain(notification));
                notificationIds
                        .computeIfAbsent(notification.projectId(), ignored -> new HashSet<>())
                        .add(notification.id());
            }
        }
        saveReadReceipts(snapshot, notificationIds);
    }

    private RuntimeNotificationSnapshot readFormalSnapshot() {
        var projects = new HashMap<String, List<RuntimeNotification>>();
        notificationMapper.selectList(new LambdaQueryWrapper<RuntimeNotificationEntity>()
                        .orderByAsc(RuntimeNotificationEntity::getProjectId)
                        .orderByDesc(RuntimeNotificationEntity::getCreatedAt)
                        .orderByAsc(RuntimeNotificationEntity::getId))
                .stream()
                .map(RuntimeNotificationEntity::toDomain)
                .forEach(notification -> projects
                        .computeIfAbsent(notification.projectId(), ignored -> new ArrayList<>())
                        .add(notification));
        return snapshot(projects, readFormalReceipts());
    }

    private Map<String, Map<String, Map<String, Instant>>> readFormalReceipts() {
        var receipts = new HashMap<String, Map<String, Map<String, Instant>>>();
        readMapper.selectList(new LambdaQueryWrapper<RuntimeNotificationReadEntity>()
                        .orderByAsc(RuntimeNotificationReadEntity::getProjectId)
                        .orderByAsc(RuntimeNotificationReadEntity::getUserId)
                        .orderByAsc(RuntimeNotificationReadEntity::getNotificationId))
                .forEach(entity -> receipts
                        .computeIfAbsent(entity.getProjectId(), ignored -> new HashMap<>())
                        .computeIfAbsent(entity.getUserId(), ignored -> new HashMap<>())
                        .put(entity.getNotificationId(), entity.getReadAt()));
        return receipts;
    }

    private RuntimeNotificationSnapshot readLegacySnapshot() {
        return snapshotRepository.load(SLICE_KEY)
                .filter(snapshot -> snapshot.version() == 1)
                .map(snapshot -> read(snapshot.payload()))
                .orElseGet(RuntimeNotificationSnapshot::empty);
    }

    private RuntimeNotificationSnapshot snapshot(
            Map<String, List<RuntimeNotification>> projects,
            Map<String, Map<String, Map<String, Instant>>> readReceipts
    ) {
        var copy = new HashMap<String, List<RuntimeNotification>>();
        projects.forEach((projectId, notifications) -> copy.put(projectId, List.copyOf(notifications)));
        return new RuntimeNotificationSnapshot(1, Map.copyOf(copy), readReceipts);
    }

    private void saveReadReceipts(RuntimeNotificationSnapshot snapshot, Map<String, Set<String>> notificationIds) {
        snapshot.readReceipts().forEach((projectId, users) -> users.forEach((userId, reads) -> {
            ensureProject(projectId, Instant.now());
            ensureUser(userId, Instant.now());
            reads.forEach((notificationId, readAt) -> {
                if (readAt == null || !notificationIds.getOrDefault(projectId, Set.of()).contains(notificationId)) {
                    return;
                }
                readMapper.insert(RuntimeNotificationReadEntity.fromReceipt(projectId, notificationId, userId, readAt));
            });
        }));
    }

    private void ensureProject(String projectId, Instant now) {
        var updatedAt = now == null ? Instant.now() : now;
        if (projectMapper.updateById(MatrixProjectEntity.touch(projectId, updatedAt)) == 0) {
            var project = MatrixProjectEntity.fallbackProject(projectId, updatedAt);
            project.setCurrentStage("运行态提醒");
            projectMapper.insert(project);
        }
    }

    private void ensureUser(String userId, Instant now) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        var normalized = userId.trim();
        var updatedAt = now == null ? Instant.now() : now;
        if (userMapper.updateById(MatrixUserEntity.touch(normalized, updatedAt)) == 0) {
            userMapper.insert(MatrixUserEntity.fallbackUser(normalized, updatedAt));
        }
    }

    private RuntimeNotificationSnapshot read(String payload) {
        try {
            var snapshot = objectMapper.readValue(payload, RuntimeNotificationSnapshot.class);
            if (snapshot.version() != 1) {
                return RuntimeNotificationSnapshot.empty();
            }
            return snapshot;
        } catch (JsonProcessingException | RuntimeException ignored) {
            return RuntimeNotificationSnapshot.empty();
        }
    }
}
