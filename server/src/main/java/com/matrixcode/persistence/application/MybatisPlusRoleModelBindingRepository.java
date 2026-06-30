package com.matrixcode.persistence.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.matrixcode.modelgateway.application.RoleModelBindingRepository;
import com.matrixcode.modelgateway.domain.RoleModelBinding;
import com.matrixcode.persistence.mybatis.entity.MatrixProjectEntity;
import com.matrixcode.persistence.mybatis.entity.RoleModelBindingEntity;
import com.matrixcode.persistence.mybatis.mapper.MatrixProjectMapper;
import com.matrixcode.persistence.mybatis.mapper.RoleModelBindingMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
@ConditionalOnProperty(prefix = "matrixcode.persistence", name = "mode", havingValue = "jdbc")
public class MybatisPlusRoleModelBindingRepository implements RoleModelBindingRepository {

    private final RoleModelBindingMapper bindingMapper;
    private final MatrixProjectMapper projectMapper;

    public MybatisPlusRoleModelBindingRepository(
            RoleModelBindingMapper bindingMapper,
            MatrixProjectMapper projectMapper
    ) {
        this.bindingMapper = bindingMapper;
        this.projectMapper = projectMapper;
    }

    /**
     * 读取全部角色模型绑定。
     *
     * <p>按项目和角色键排序，确保重启恢复后的模型绑定顺序稳定。</p>
     */
    @Override
    public List<RoleModelBinding> load() {
        return bindingMapper.selectList(new LambdaQueryWrapper<RoleModelBindingEntity>()
                        .orderByAsc(RoleModelBindingEntity::getProjectId)
                        .orderByAsc(RoleModelBindingEntity::getRoleKey))
                .stream()
                .map(RoleModelBindingEntity::toDomain)
                .toList();
    }

    /**
     * 批量 upsert 角色模型绑定。
     *
     * <p>保存前补齐项目外键；绑定使用稳定 ID 覆盖同一项目同一角色的模型选择。</p>
     */
    @Override
    @Transactional
    public void save(List<RoleModelBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return;
        }
        var now = Instant.now();
        for (var binding : bindings) {
            ensureProject(binding.projectId(), now);
            var entity = RoleModelBindingEntity.fromDomain(binding, now);
            if (bindingMapper.updateById(entity) == 0) {
                bindingMapper.insert(entity);
            }
        }
    }

    private void ensureProject(String projectId, Instant now) {
        if (projectMapper.updateById(MatrixProjectEntity.touch(projectId, now)) == 0) {
            var project = MatrixProjectEntity.fallbackProject(projectId, now);
            project.setCurrentStage("角色模型绑定");
            projectMapper.insert(project);
        }
    }
}
