package com.matrixcode.modelgateway.application;

import com.matrixcode.modelgateway.domain.ModelRole;

import java.util.List;

/**
 * 向量上下文存储接口。
 *
 * <p>作用域：RAG 上下文存储；场景：支持内存模式和 Milvus 模式的上下文写入、相似度搜索。</p>
 */
public interface VectorContextStore {

    /**
     * 新增或更新一条上下文向量记录。
     */
    void upsert(VectorContextEntry entry);

    /**
     * 按项目和角色搜索最相关的上下文。
     */
    List<VectorContextHit> search(String projectId, ModelRole role, List<Float> embedding, int topK);
}
