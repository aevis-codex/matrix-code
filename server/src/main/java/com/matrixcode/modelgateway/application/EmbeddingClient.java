package com.matrixcode.modelgateway.application;

import java.util.List;

/**
 * 文本向量化客户端接口。
 *
 * <p>作用域：向量上下文写入和召回；场景：调用供应商 embedding 模型生成 Milvus 可检索向量。</p>
 */
public interface EmbeddingClient {

    /**
     * 将输入文本转换为浮点向量。
     */
    List<Float> embed(String input);
}
