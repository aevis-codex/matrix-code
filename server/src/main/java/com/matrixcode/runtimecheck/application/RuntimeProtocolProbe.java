package com.matrixcode.runtimecheck.application;

import com.matrixcode.modelgateway.application.ModelGatewayProperties;
import com.matrixcode.persistence.application.PersistenceModeProperties;

/**
 * 外部依赖协议级探针。
 *
 * <p>TCP 只能证明端口可达；协议探针用于确认 JDBC 登录、SQL 查询、Milvus gRPC 调用等真实运行能力。</p>
 */
public interface RuntimeProtocolProbe {

    /**
     * 检查 MySQL JDBC 协议是否可用。
     *
     * @param jdbc JDBC 运行配置。
     * @return 协议检查结果。
     */
    RuntimeProtocolProbeResult checkJdbc(PersistenceModeProperties.Jdbc jdbc);

    /**
     * 检查 Milvus gRPC 协议是否可用。
     *
     * @param milvus Milvus 运行配置。
     * @return 协议检查结果。
     */
    RuntimeProtocolProbeResult checkMilvus(ModelGatewayProperties.Milvus milvus);

    /**
     * 单元测试兼容探针：不访问真实外部服务。
     */
    static RuntimeProtocolProbe noop() {
        return new RuntimeProtocolProbe() {
            @Override
            public RuntimeProtocolProbeResult checkJdbc(PersistenceModeProperties.Jdbc jdbc) {
                return RuntimeProtocolProbeResult.pass("JDBC 协议检查已跳过");
            }

            @Override
            public RuntimeProtocolProbeResult checkMilvus(ModelGatewayProperties.Milvus milvus) {
                return RuntimeProtocolProbeResult.pass("Milvus 协议检查已跳过");
            }
        };
    }
}
