package com.matrixcode.runtimecheck.application;

import java.time.Duration;

/**
 * TCP 连通性探针接口。
 *
 * <p>作用域：运行诊断和真实运行门禁；场景：在协议检查前快速判断外部依赖端口是否可达。</p>
 */
@FunctionalInterface
public interface TcpConnectivityProbe {

    /**
     * 在指定超时时间内尝试建立 TCP 连接。
     */
    boolean canConnect(String host, int port, Duration timeout);
}
