package com.matrixcode.persistence.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.matrixcode.deployment.application.DeploymentRuntimeRepository;
import com.matrixcode.deployment.domain.ComposeEnvironment;
import com.matrixcode.deployment.domain.ComposeOperationRecord;
import com.matrixcode.deployment.domain.DeploymentHealthCheck;
import com.matrixcode.deployment.domain.DeploymentOperationRecord;
import com.matrixcode.persistence.mybatis.entity.ComposeEnvironmentEntity;
import com.matrixcode.persistence.mybatis.entity.ComposeOperationEntity;
import com.matrixcode.persistence.mybatis.entity.DeploymentHealthCheckEntity;
import com.matrixcode.persistence.mybatis.entity.DeploymentOperationEntity;
import com.matrixcode.persistence.mybatis.entity.MatrixProjectEntity;
import com.matrixcode.persistence.mybatis.mapper.ComposeEnvironmentMapper;
import com.matrixcode.persistence.mybatis.mapper.ComposeOperationMapper;
import com.matrixcode.persistence.mybatis.mapper.DeploymentHealthCheckMapper;
import com.matrixcode.persistence.mybatis.mapper.DeploymentOperationMapper;
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
public class MybatisPlusDeploymentRuntimeRepository implements DeploymentRuntimeRepository {

    private final DeploymentOperationMapper operationMapper;
    private final DeploymentHealthCheckMapper healthCheckMapper;
    private final ComposeEnvironmentMapper composeEnvironmentMapper;
    private final ComposeOperationMapper composeOperationMapper;
    private final MatrixProjectMapper projectMapper;

    public MybatisPlusDeploymentRuntimeRepository(
            DeploymentOperationMapper operationMapper,
            DeploymentHealthCheckMapper healthCheckMapper,
            ComposeEnvironmentMapper composeEnvironmentMapper,
            ComposeOperationMapper composeOperationMapper,
            MatrixProjectMapper projectMapper
    ) {
        this.operationMapper = operationMapper;
        this.healthCheckMapper = healthCheckMapper;
        this.composeEnvironmentMapper = composeEnvironmentMapper;
        this.composeOperationMapper = composeOperationMapper;
        this.projectMapper = projectMapper;
    }

    /**
     * 读取全部部署操作记录。
     *
     * <p>排序保持旧 JDBC 仓储行为：项目升序、发生时间倒序、ID 升序，确保工作台部署摘要
     * 在仓储切换后仍能稳定展示最新部署和回滚记录。</p>
     */
    @Override
    public Map<String, List<DeploymentOperationRecord>> loadDeploymentOperations() {
        var grouped = new LinkedHashMap<String, List<DeploymentOperationRecord>>();
        operationMapper.selectList(new LambdaQueryWrapper<DeploymentOperationEntity>()
                        .orderByAsc(DeploymentOperationEntity::getProjectId)
                        .orderByDesc(DeploymentOperationEntity::getCreatedAt)
                        .orderByAsc(DeploymentOperationEntity::getId))
                .stream()
                .map(DeploymentOperationEntity::toDomain)
                .forEach(record -> grouped.computeIfAbsent(record.projectId(), ignored -> new ArrayList<>()).add(record));
        return immutableGrouped(grouped);
    }

    /**
     * 保存部署操作快照。
     *
     * <p>部署操作服务按项目维护内存快照，本方法延续旧 JDBC 语义：每个项目先删除旧记录，
     * 再写入当前快照，避免撤销或裁剪后的历史记录在正式表残留。</p>
     */
    @Override
    @Transactional
    public void saveDeploymentOperations(Map<String, List<DeploymentOperationRecord>> operations) {
        if (operations == null || operations.isEmpty()) {
            return;
        }
        operations.forEach((projectId, records) -> {
            ensureProject(projectId, "部署操作", Instant.now());
            operationMapper.delete(new LambdaQueryWrapper<DeploymentOperationEntity>()
                    .eq(DeploymentOperationEntity::getProjectId, projectId));
            records.forEach(record -> operationMapper.insert(DeploymentOperationEntity.fromDomain(record)));
        });
    }

    @Override
    public Map<String, List<DeploymentHealthCheck>> loadDeploymentHealthChecks() {
        var grouped = new LinkedHashMap<String, List<DeploymentHealthCheck>>();
        healthCheckMapper.selectList(new LambdaQueryWrapper<DeploymentHealthCheckEntity>()
                        .orderByAsc(DeploymentHealthCheckEntity::getProjectId)
                        .orderByDesc(DeploymentHealthCheckEntity::getCheckedAt)
                        .orderByAsc(DeploymentHealthCheckEntity::getId))
                .stream()
                .map(DeploymentHealthCheckEntity::toDomain)
                .forEach(check -> grouped.computeIfAbsent(check.projectId(), ignored -> new ArrayList<>()).add(check));
        return immutableGrouped(grouped);
    }

    @Override
    @Transactional
    public void saveDeploymentHealthChecks(Map<String, List<DeploymentHealthCheck>> checks) {
        if (checks == null || checks.isEmpty()) {
            return;
        }
        checks.forEach((projectId, records) -> {
            ensureProject(projectId, "部署健康检查", Instant.now());
            healthCheckMapper.delete(new LambdaQueryWrapper<DeploymentHealthCheckEntity>()
                    .eq(DeploymentHealthCheckEntity::getProjectId, projectId));
            records.forEach(check -> healthCheckMapper.insert(DeploymentHealthCheckEntity.fromDomain(check)));
        });
    }

    @Override
    public List<ComposeEnvironment> loadComposeEnvironments() {
        return composeEnvironmentMapper.selectList(new LambdaQueryWrapper<ComposeEnvironmentEntity>()
                        .orderByAsc(ComposeEnvironmentEntity::getProjectId)
                        .orderByAsc(ComposeEnvironmentEntity::getCreatedAt)
                        .orderByAsc(ComposeEnvironmentEntity::getId))
                .stream()
                .map(ComposeEnvironmentEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public void saveComposeEnvironments(List<ComposeEnvironment> environments) {
        if (environments == null || environments.isEmpty()) {
            return;
        }
        environments.forEach(environment -> {
            ensureProject(environment.projectId(), "Compose 环境", environment.updatedAt());
            var entity = ComposeEnvironmentEntity.fromDomain(environment);
            if (composeEnvironmentMapper.updateById(entity) == 0) {
                composeEnvironmentMapper.insert(entity);
            }
        });
    }

    @Override
    public Map<String, List<ComposeOperationRecord>> loadComposeOperations() {
        var grouped = new LinkedHashMap<String, List<ComposeOperationRecord>>();
        composeOperationMapper.selectList(new LambdaQueryWrapper<ComposeOperationEntity>()
                        .orderByAsc(ComposeOperationEntity::getProjectId)
                        .orderByDesc(ComposeOperationEntity::getCreatedAt)
                        .orderByAsc(ComposeOperationEntity::getId))
                .stream()
                .map(ComposeOperationEntity::toDomain)
                .forEach(record -> grouped.computeIfAbsent(record.projectId(), ignored -> new ArrayList<>()).add(record));
        return immutableGrouped(grouped);
    }

    @Override
    @Transactional
    public void saveComposeOperations(Map<String, List<ComposeOperationRecord>> operations) {
        if (operations == null || operations.isEmpty()) {
            return;
        }
        operations.forEach((projectId, records) -> {
            ensureProject(projectId, "Compose 操作", Instant.now());
            composeOperationMapper.delete(new LambdaQueryWrapper<ComposeOperationEntity>()
                    .eq(ComposeOperationEntity::getProjectId, projectId));
            records.forEach(record -> composeOperationMapper.insert(ComposeOperationEntity.fromDomain(record)));
        });
    }

    private void ensureProject(String projectId, String stage, Instant now) {
        var updatedAt = now == null ? Instant.now() : now;
        if (projectMapper.updateById(MatrixProjectEntity.touch(projectId, updatedAt)) == 0) {
            var project = MatrixProjectEntity.fallbackProject(projectId, updatedAt);
            project.setCurrentStage(stage);
            projectMapper.insert(project);
        }
    }

    private <T> Map<String, List<T>> immutableGrouped(Map<String, List<T>> grouped) {
        var copy = new LinkedHashMap<String, List<T>>();
        grouped.forEach((projectId, records) -> copy.put(projectId, List.copyOf(records)));
        return Map.copyOf(copy);
    }
}
