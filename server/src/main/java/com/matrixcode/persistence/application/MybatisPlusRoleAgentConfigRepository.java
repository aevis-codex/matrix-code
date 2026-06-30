package com.matrixcode.persistence.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.matrixcode.persistence.mybatis.entity.MatrixProjectEntity;
import com.matrixcode.persistence.mybatis.entity.RoleAgentConfigEntity;
import com.matrixcode.persistence.mybatis.mapper.MatrixProjectMapper;
import com.matrixcode.persistence.mybatis.mapper.RoleAgentConfigMapper;
import com.matrixcode.roleagent.application.RoleAgentConfigRepository;
import com.matrixcode.roleagent.domain.RoleAgentConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
@ConditionalOnProperty(prefix = "matrixcode.persistence", name = "mode", havingValue = "jdbc")
public class MybatisPlusRoleAgentConfigRepository implements RoleAgentConfigRepository {

    private final RoleAgentConfigMapper configMapper;
    private final MatrixProjectMapper projectMapper;

    public MybatisPlusRoleAgentConfigRepository(
            RoleAgentConfigMapper configMapper,
            MatrixProjectMapper projectMapper
    ) {
        this.configMapper = configMapper;
        this.projectMapper = projectMapper;
    }

    /**
     * 读取全部项目的角色智能体配置。
     *
     * <p>结果按项目、角色排序值、角色键稳定排序，前端配置面板和审计导出可以获得一致顺序。</p>
     */
    @Override
    public List<RoleAgentConfig> load() {
        return configMapper.selectList(new LambdaQueryWrapper<RoleAgentConfigEntity>()
                        .orderByAsc(RoleAgentConfigEntity::getProjectId)
                        .orderByAsc(RoleAgentConfigEntity::getSortOrder)
                        .orderByAsc(RoleAgentConfigEntity::getRoleKey))
                .stream()
                .map(RoleAgentConfigEntity::toDomain)
                .toList();
    }

    /**
     * 批量保存角色智能体配置。
     *
     * <p>每条配置写入前都会补齐项目外键；配置本身使用稳定主键 upsert，
     * 便于管理员在页面上反复调整模型、提示词、主题色和字体配置。</p>
     */
    @Override
    @Transactional
    public void save(List<RoleAgentConfig> configs) {
        if (configs == null || configs.isEmpty()) {
            return;
        }
        for (var config : configs) {
            ensureProject(config.projectId(), config.updatedAt());
            var entity = RoleAgentConfigEntity.fromDomain(config);
            if (configMapper.updateById(entity) == 0) {
                configMapper.insert(entity);
            }
        }
    }

    private void ensureProject(String projectId, Instant now) {
        var updatedAt = now == null ? Instant.now() : now;
        if (projectMapper.updateById(MatrixProjectEntity.touch(projectId, updatedAt)) == 0) {
            projectMapper.insert(roleConfigProject(projectId, updatedAt));
        }
    }

    private MatrixProjectEntity roleConfigProject(String projectId, Instant now) {
        var entity = MatrixProjectEntity.fallbackProject(projectId, now);
        entity.setCurrentStage("角色智能体配置");
        return entity;
    }
}
