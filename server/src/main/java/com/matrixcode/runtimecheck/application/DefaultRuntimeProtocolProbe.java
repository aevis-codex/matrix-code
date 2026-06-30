package com.matrixcode.runtimecheck.application;

import com.matrixcode.modelgateway.application.ModelGatewayProperties;
import com.matrixcode.persistence.application.PersistenceModeProperties;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import org.springframework.stereotype.Component;

import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * 默认真实协议探针实现。
 *
 * <p>该实现只做低成本读探测：MySQL 执行 `SELECT 1`，Milvus 读取 database 列表并检查目标 collection
 * 是否可查询存在状态。诊断过程中不创建数据库、不创建 collection、不写业务数据。</p>
 */
@Component
public class DefaultRuntimeProtocolProbe implements RuntimeProtocolProbe {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int RPC_DEADLINE_MS = 10_000;

    /**
     * 通过 JDBC 登录并执行 `SELECT 1`，确认 MySQL 不只是端口开放。
     */
    @Override
    public RuntimeProtocolProbeResult checkJdbc(PersistenceModeProperties.Jdbc jdbc) {
        try (var connection = DriverManager.getConnection(jdbc.getUrl(), jdbc.getUsername(), jdbc.getPassword());
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT 1")) {
            if (resultSet.next()) {
                return RuntimeProtocolProbeResult.pass("JDBC 登录和 SELECT 1 成功");
            }
            return RuntimeProtocolProbeResult.fail("JDBC 查询未返回结果");
        } catch (SQLException exception) {
            return RuntimeProtocolProbeResult.fail("JDBC 协议检查失败：" + safeSqlMessage(exception));
        }
    }

    /**
     * 通过 Milvus gRPC 客户端读取 database 列表，并在目标 database 内检查 collection 状态。
     */
    @Override
    public RuntimeProtocolProbeResult checkMilvus(ModelGatewayProperties.Milvus milvus) {
        MilvusClientV2 bootstrapClient = null;
        MilvusClientV2 databaseClient = null;
        try {
            bootstrapClient = new MilvusClientV2(connectConfig(milvus, false));
            var databases = bootstrapClient.listDatabases().getDatabaseNames();
            if (!milvus.getDatabase().isBlank() && !databases.contains(milvus.getDatabase())) {
                return RuntimeProtocolProbeResult.fail("Milvus database 不存在：" + milvus.getDatabase());
            }
            databaseClient = new MilvusClientV2(connectConfig(milvus, true));
            databaseClient.hasCollection(HasCollectionReq.builder()
                    .collectionName(milvus.getCollection())
                    .build());
            return RuntimeProtocolProbeResult.pass("Milvus gRPC 和 database 检查成功");
        } catch (RuntimeException exception) {
            return RuntimeProtocolProbeResult.fail("Milvus 协议检查失败：" + safeRuntimeMessage(exception));
        } finally {
            if (databaseClient != null) {
                databaseClient.close();
            }
            if (bootstrapClient != null) {
                bootstrapClient.close();
            }
        }
    }

    private ConnectConfig connectConfig(ModelGatewayProperties.Milvus milvus, boolean includeDatabase) {
        var builder = ConnectConfig.builder()
                .uri("%s://%s:%d".formatted(milvus.isSecure() ? "https" : "http", milvus.getHost(), milvus.getPort()))
                .connectTimeoutMs(CONNECT_TIMEOUT_MS)
                .rpcDeadlineMs(RPC_DEADLINE_MS);
        if (includeDatabase && !milvus.getDatabase().isBlank()) {
            builder.dbName(milvus.getDatabase());
        }
        if (!milvus.getToken().isBlank()) {
            builder.token(milvus.getToken());
        }
        if (!milvus.getUsername().isBlank()) {
            builder.username(milvus.getUsername());
            builder.password(milvus.getPassword());
        }
        return builder.build();
    }

    private String safeSqlMessage(SQLException exception) {
        var sqlState = exception.getSQLState() == null ? "unknown" : exception.getSQLState();
        return "%s SQLState=%s".formatted(exception.getClass().getSimpleName(), sqlState);
    }

    private String safeRuntimeMessage(RuntimeException exception) {
        return exception.getClass().getSimpleName();
    }
}
