package com.matrixcode.persistence.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.matrixcode.deployment.application.DeploymentTargetRepository;
import com.matrixcode.deployment.domain.DeploymentTarget;
import com.matrixcode.persistence.mybatis.entity.DeploymentTargetEntity;
import com.matrixcode.persistence.mybatis.entity.MatrixProjectEntity;
import com.matrixcode.persistence.mybatis.mapper.DeploymentTargetMapper;
import com.matrixcode.persistence.mybatis.mapper.MatrixProjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
@ConditionalOnProperty(prefix = "matrixcode.persistence", name = "mode", havingValue = "jdbc")
public class MybatisPlusDeploymentTargetRepository implements DeploymentTargetRepository {

    private final DeploymentTargetMapper targetMapper;
    private final MatrixProjectMapper projectMapper;

    public MybatisPlusDeploymentTargetRepository(
            DeploymentTargetMapper targetMapper,
            MatrixProjectMapper projectMapper
    ) {
        this.targetMapper = targetMapper;
        this.projectMapper = projectMapper;
    }

    /**
     * 读取全部项目的部署目标。
     *
     * <p>排序规则保持旧 JDBC 仓储行为：项目、环境名、ID 稳定排序，确保工作台和运维视图
     * 在服务重启或仓储实现替换后仍展示一致顺序。</p>
     */
    @Override
    public List<DeploymentTarget> load() {
        return targetMapper.selectList(new LambdaQueryWrapper<DeploymentTargetEntity>()
                        .orderByAsc(DeploymentTargetEntity::getProjectId)
                        .orderByAsc(DeploymentTargetEntity::getName)
                        .orderByAsc(DeploymentTargetEntity::getId))
                .stream()
                .map(DeploymentTargetEntity::toDomain)
                .toList();
    }

    /**
     * 批量保存部署目标。
     *
     * <p>部署目标是项目级增量配置，不是全量快照；因此这里按主键逐条 upsert，
     * 不会清空同项目下的其他部署环境。写入前会补齐项目外键。</p>
     */
    @Override
    @Transactional
    public void save(List<DeploymentTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return;
        }
        for (var target : targets) {
            ensureProject(target.projectId(), target.updatedAt());
            var entity = DeploymentTargetEntity.fromDomain(target);
            if (targetMapper.updateById(entity) == 0) {
                targetMapper.insert(entity);
            }
        }
    }

    private void ensureProject(String projectId, Instant now) {
        var updatedAt = now == null ? Instant.now() : now;
        if (projectMapper.updateById(MatrixProjectEntity.touch(projectId, updatedAt)) == 0) {
            var project = MatrixProjectEntity.fallbackProject(projectId, updatedAt);
            project.setCurrentStage("部署目标");
            projectMapper.insert(project);
        }
    }
}
