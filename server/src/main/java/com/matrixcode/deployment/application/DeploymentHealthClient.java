package com.matrixcode.deployment.application;

import java.net.URI;

/**
 * 部署健康检查客户端接口。
 *
 * <p>作用域：运维健康探测；场景：对部署目标的健康 URL 发起 HTTP 检查并返回低敏结果。</p>
 */
@FunctionalInterface
public interface DeploymentHealthClient {

    /**
     * 检查指定健康探测地址。
     */
    DeploymentHealthProbe check(URI uri);
}
