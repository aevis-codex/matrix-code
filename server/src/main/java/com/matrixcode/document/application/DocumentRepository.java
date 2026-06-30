package com.matrixcode.document.application;

import com.matrixcode.document.domain.DocumentVersion;

import java.util.List;

/**
 * 项目文档仓储接口。
 *
 * <p>作用域：文档中心；场景：保存 PRD、界面说明、验收标准、测试报告和编码交接文档版本。</p>
 */
public interface DocumentRepository {

    /**
     * 读取所有文档版本。
     */
    List<DocumentVersion> load();

    /**
     * 保存当前文档版本集合。
     */
    void save(List<DocumentVersion> documents);
}
