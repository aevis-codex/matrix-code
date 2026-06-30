package com.matrixcode.persistence.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.matrixcode.bug.application.BugRepository;
import com.matrixcode.bug.domain.ProjectBug;
import com.matrixcode.persistence.mybatis.entity.BugEntity;
import com.matrixcode.persistence.mybatis.entity.MatrixProjectEntity;
import com.matrixcode.persistence.mybatis.mapper.BugMapper;
import com.matrixcode.persistence.mybatis.mapper.MatrixProjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
@ConditionalOnProperty(prefix = "matrixcode.persistence", name = "mode", havingValue = "jdbc")
public class MybatisPlusBugRepository implements BugRepository {

    private final BugMapper bugMapper;
    private final MatrixProjectMapper projectMapper;

    public MybatisPlusBugRepository(BugMapper bugMapper, MatrixProjectMapper projectMapper) {
        this.bugMapper = bugMapper;
        this.projectMapper = projectMapper;
    }

    /**
     * 读取所有项目 Bug。
     *
     * <p>排序规则保持旧 JDBC 仓储行为：项目、标题、ID 稳定排序，避免工作台缺陷列表在仓储
     * 替换后出现顺序漂移。</p>
     */
    @Override
    public List<ProjectBug> load() {
        return bugMapper.selectList(new LambdaQueryWrapper<BugEntity>()
                        .orderByAsc(BugEntity::getProjectId)
                        .orderByAsc(BugEntity::getTitle)
                        .orderByAsc(BugEntity::getId))
                .stream()
                .map(BugEntity::toDomain)
                .toList();
    }

    /**
     * 批量保存 Bug。
     *
     * <p>Bug 仓储按 Bug ID 增量 upsert，不删除同项目下其他缺陷；写入前补齐项目外键，
     * 以保持真实 MySQL 外键约束下的旧 JDBC 行为。</p>
     */
    @Override
    @Transactional
    public void save(List<ProjectBug> bugs) {
        if (bugs == null || bugs.isEmpty()) {
            return;
        }
        for (var bug : bugs) {
            ensureProject(bug.projectId(), bug.updatedAt());
            var entity = BugEntity.fromDomain(bug);
            if (bugMapper.updateById(entity) == 0) {
                bugMapper.insert(entity);
            }
        }
    }

    private void ensureProject(String projectId, Instant now) {
        var timestamp = now == null ? Instant.now() : now;
        if (projectMapper.updateById(MatrixProjectEntity.touch(projectId, timestamp)) == 0) {
            var project = MatrixProjectEntity.fallbackProject(projectId, timestamp);
            project.setCurrentStage("Bug 缺陷闭环");
            projectMapper.insert(project);
        }
    }
}
