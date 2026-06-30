package com.matrixcode.persistence.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.matrixcode.document.application.DocumentRepository;
import com.matrixcode.document.domain.DocumentVersion;
import com.matrixcode.persistence.mybatis.entity.DocumentEntity;
import com.matrixcode.persistence.mybatis.entity.MatrixProjectEntity;
import com.matrixcode.persistence.mybatis.mapper.DocumentMapper;
import com.matrixcode.persistence.mybatis.mapper.MatrixProjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
@ConditionalOnProperty(prefix = "matrixcode.persistence", name = "mode", havingValue = "jdbc")
public class MybatisPlusDocumentRepository implements DocumentRepository {

    private final DocumentMapper documentMapper;
    private final MatrixProjectMapper projectMapper;

    public MybatisPlusDocumentRepository(DocumentMapper documentMapper, MatrixProjectMapper projectMapper) {
        this.documentMapper = documentMapper;
        this.projectMapper = projectMapper;
    }

    /**
     * 读取所有项目文档版本。
     *
     * <p>排序规则保持旧 JDBC 仓储行为：项目、标题、版本、ID 稳定排序，保证工作台文档列表
     * 和冻结后交接文档展示顺序在仓储替换后不漂移。</p>
     */
    @Override
    public List<DocumentVersion> load() {
        return documentMapper.selectList(new LambdaQueryWrapper<DocumentEntity>()
                        .orderByAsc(DocumentEntity::getProjectId)
                        .orderByAsc(DocumentEntity::getTitle)
                        .orderByAsc(DocumentEntity::getVersion)
                        .orderByAsc(DocumentEntity::getId))
                .stream()
                .map(DocumentEntity::toDomain)
                .toList();
    }

    /**
     * 批量保存文档版本。
     *
     * <p>文档中心保存的是按文档 ID 增量 upsert 的版本集合，不是全项目快照；因此这里逐条更新
     * 或插入，不会删除同项目下的其他文档。写入前会补齐项目外键，避免真实 MySQL 外键约束失败。</p>
     */
    @Override
    @Transactional
    public void save(List<DocumentVersion> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        for (var document : documents) {
            ensureProject(document.projectId(), timestampFor(document));
            var entity = DocumentEntity.fromDomain(document);
            if (documentMapper.updateById(entity) == 0) {
                documentMapper.insert(entity);
            }
        }
    }

    private void ensureProject(String projectId, Instant now) {
        var timestamp = now == null ? Instant.now() : now;
        if (projectMapper.updateById(MatrixProjectEntity.touch(projectId, timestamp)) == 0) {
            var project = MatrixProjectEntity.fallbackProject(projectId, timestamp);
            project.setCurrentStage("文档中心");
            projectMapper.insert(project);
        }
    }

    private Instant timestampFor(DocumentVersion document) {
        if (document.frozenAt() != null) {
            return document.frozenAt();
        }
        return document.createdAt();
    }
}
