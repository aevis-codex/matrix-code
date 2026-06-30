package com.matrixcode.persistence.application;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcConnectionFactoryTest {

    @Test
    void 通信异常会短暂重试并返回后续成功连接() throws Exception {
        var jdbc = jdbc();
        var attempts = new AtomicInteger();
        var expected = proxyConnection();

        var connection = JdbcConnectionFactory.open(jdbc, (url, username, password) -> {
            if (attempts.incrementAndGet() < 3) {
                throw new SQLException("Communications link failure", "08S01");
            }
            return expected;
        }, ignored -> {
        });

        assertThat(connection).isSameAs(expected);
        assertThat(attempts).hasValue(3);
    }

    @Test
    void 非通信异常不会重试避免掩盖配置错误() {
        var jdbc = jdbc();
        var attempts = new AtomicInteger();

        assertThatThrownBy(() -> JdbcConnectionFactory.open(jdbc, (url, username, password) -> {
            attempts.incrementAndGet();
            throw new SQLException("Unknown database", "42000");
        }, ignored -> {
        }))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Unknown database");
        assertThat(attempts).hasValue(1);
    }

    private PersistenceModeProperties.Jdbc jdbc() {
        var jdbc = new PersistenceModeProperties.Jdbc();
        jdbc.setUrl("jdbc:test://localhost/matrix_code");
        jdbc.setUsername("matrix");
        jdbc.setPassword("secret");
        return jdbc;
    }

    private Connection proxyConnection() {
        return (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> null
        );
    }
}
