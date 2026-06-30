package com.matrixcode.deployment.application;

/**
 * Compose 运行时客户端接口。
 *
 * <p>作用域：运维演示环境；场景：封装 docker compose 校验、启动、停止和日志采集动作。</p>
 */
public interface ComposeRuntimeClient {
    /**
     * 校验 Compose 环境配置是否可执行。
     */
    ComposeRuntimeResult validate(ComposeRuntimeRequest request);

    /**
     * 启动 Compose 环境。
     */
    ComposeRuntimeResult start(ComposeRuntimeRequest request);

    /**
     * 停止 Compose 环境。
     */
    ComposeRuntimeResult stop(ComposeRuntimeRequest request);

    /**
     * 采集 Compose 环境日志。
     */
    ComposeRuntimeResult logs(ComposeRuntimeRequest request);
}
