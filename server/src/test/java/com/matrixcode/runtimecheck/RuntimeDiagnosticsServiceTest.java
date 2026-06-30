package com.matrixcode.runtimecheck;

import com.matrixcode.modelgateway.application.ModelGatewayProperties;
import com.matrixcode.persistence.application.PersistenceModeProperties;
import com.matrixcode.runtimecheck.application.RuntimeProtocolProbe;
import com.matrixcode.runtimecheck.application.RuntimeProtocolProbeResult;
import com.matrixcode.runtimecheck.application.RuntimeDiagnosticsService;
import com.matrixcode.runtimecheck.domain.RuntimeCheckStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeDiagnosticsServiceTest {

    @Test
    void jdbc模式缺少真实密码会返回阻塞失败() {
        var persistence = jdbcProperties(
                "jdbc:mysql://127.0.0.1:3306/matrix_code?useSSL=false",
                "root",
                "change-me"
        );
        var service = new RuntimeDiagnosticsService(persistence, new ModelGatewayProperties(), (host, port, timeout) -> true);

        var report = service.inspect();

        assertThat(report.status()).isEqualTo(RuntimeCheckStatus.FAIL);
        assertThat(report.items()).anySatisfy(item -> {
            assertThat(item.key()).isEqualTo("mysql.credentials");
            assertThat(item.blocking()).isTrue();
            assertThat(item.status()).isEqualTo(RuntimeCheckStatus.FAIL);
        });
    }

    @Test
    void milvus不可达会阻塞向量上下文真实运行() {
        var persistence = jdbcProperties(
                "jdbc:mysql://127.0.0.1:3306/matrix_code?useSSL=false",
                "root",
                "secret"
        );
        var modelGateway = new ModelGatewayProperties();
        modelGateway.getVectorContext().setEnabled(true);
        modelGateway.getVectorContext().setStore("milvus");
        modelGateway.getMilvus().setHost("127.0.0.1");
        modelGateway.getMilvus().setPort(19530);
        modelGateway.getMilvus().setCollection("matrixcode_context_chunks_v2");
        var service = new RuntimeDiagnosticsService(persistence, modelGateway, (host, port, timeout) -> port != 19530);

        var report = service.inspect();

        assertThat(report.status()).isEqualTo(RuntimeCheckStatus.FAIL);
        assertThat(report.items()).anySatisfy(item -> {
            assertThat(item.key()).isEqualTo("milvus.connectivity");
            assertThat(item.blocking()).isTrue();
            assertThat(item.status()).isEqualTo(RuntimeCheckStatus.FAIL);
        });
    }

    @Test
    void redis和rocketmq不可达只产生非阻塞警告() {
        var persistence = jdbcProperties(
                "jdbc:mysql://127.0.0.1:3306/matrix_code?useSSL=false",
                "root",
                "secret"
        );
        var service = new RuntimeDiagnosticsService(persistence, new ModelGatewayProperties(), (host, port, timeout) -> false);

        var report = service.inspect();

        assertThat(report.status()).isEqualTo(RuntimeCheckStatus.FAIL);
        assertThat(report.items()).anySatisfy(item -> {
            assertThat(item.key()).isEqualTo("redis.connectivity");
            assertThat(item.blocking()).isFalse();
            assertThat(item.status()).isEqualTo(RuntimeCheckStatus.WARN);
        });
        assertThat(report.items()).anySatisfy(item -> {
            assertThat(item.key()).isEqualTo("rocketmq.connectivity");
            assertThat(item.blocking()).isFalse();
            assertThat(item.status()).isEqualTo(RuntimeCheckStatus.WARN);
        });
    }

    @Test
    void tcp通过但mysql协议失败会返回阻塞失败() {
        var persistence = jdbcProperties(
                "jdbc:mysql://127.0.0.1:3306/matrix_code?useSSL=false",
                "root",
                "secret"
        );
        var service = new RuntimeDiagnosticsService(
                persistence,
                new ModelGatewayProperties(),
                (host, port, timeout) -> true,
                protocolProbe(false, true)
        );

        var report = service.inspect();

        assertThat(report.status()).isEqualTo(RuntimeCheckStatus.FAIL);
        assertThat(report.items()).anySatisfy(item -> {
            assertThat(item.key()).isEqualTo("mysql.protocol");
            assertThat(item.blocking()).isTrue();
            assertThat(item.status()).isEqualTo(RuntimeCheckStatus.FAIL);
        });
    }

    @Test
    void tcp通过但milvus协议失败会返回阻塞失败() {
        var persistence = jdbcProperties(
                "jdbc:mysql://127.0.0.1:3306/matrix_code?useSSL=false",
                "root",
                "secret"
        );
        var modelGateway = new ModelGatewayProperties();
        modelGateway.getVectorContext().setEnabled(true);
        modelGateway.getVectorContext().setStore("milvus");
        var service = new RuntimeDiagnosticsService(
                persistence,
                modelGateway,
                (host, port, timeout) -> true,
                protocolProbe(true, false)
        );

        var report = service.inspect();

        assertThat(report.status()).isEqualTo(RuntimeCheckStatus.FAIL);
        assertThat(report.items()).anySatisfy(item -> {
            assertThat(item.key()).isEqualTo("milvus.protocol");
            assertThat(item.blocking()).isTrue();
            assertThat(item.status()).isEqualTo(RuntimeCheckStatus.FAIL);
        });
    }

    private PersistenceModeProperties jdbcProperties(String url, String username, String password) {
        var properties = new PersistenceModeProperties();
        properties.setMode("jdbc");
        properties.getJdbc().setUrl(url);
        properties.getJdbc().setUsername(username);
        properties.getJdbc().setPassword(password);
        return properties;
    }

    private RuntimeProtocolProbe protocolProbe(boolean mysqlPass, boolean milvusPass) {
        return new RuntimeProtocolProbe() {
            @Override
            public RuntimeProtocolProbeResult checkJdbc(PersistenceModeProperties.Jdbc jdbc) {
                return result(mysqlPass, "MySQL JDBC 协议检查");
            }

            @Override
            public RuntimeProtocolProbeResult checkMilvus(ModelGatewayProperties.Milvus milvus) {
                return result(milvusPass, "Milvus gRPC 协议检查");
            }

            private RuntimeProtocolProbeResult result(boolean passed, String label) {
                return passed
                        ? RuntimeProtocolProbeResult.pass(label + "通过")
                        : RuntimeProtocolProbeResult.fail(label + "失败");
            }
        };
    }
}
