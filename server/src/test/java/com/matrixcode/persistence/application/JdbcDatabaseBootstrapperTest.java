package com.matrixcode.persistence.application;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcDatabaseBootstrapperTest {

    @Test
    void 启用自动建库时从MysqlJdbcUrl提取库名并执行建库语句() {
        var calls = new ArrayList<String>();
        var bootstrapper = new JdbcDatabaseBootstrapper((url, username, password) -> {
            calls.add("connect " + url + " " + username + " " + password);
            return connection(calls);
        });

        bootstrapper.createDatabaseIfRequired(
                "jdbc:mysql://127.0.0.1:3306/matrix_code?useSSL=false&serverTimezone=Asia/Shanghai",
                "root",
                "secret"
        );

        assertThat(calls).containsExactly(
                "connect jdbc:mysql://127.0.0.1:3306/?useSSL=false&serverTimezone=Asia/Shanghai root secret",
                "execute create database if not exists `matrix_code` default character set utf8mb4 collate utf8mb4_unicode_ci",
                "statement.close",
                "connection.close"
        );
    }

    @Test
    void 非MysqlJdbcUrl不执行自动建库() {
        var calls = new ArrayList<String>();
        var bootstrapper = new JdbcDatabaseBootstrapper((url, username, password) -> {
            calls.add("connect");
            return connection(calls);
        });

        bootstrapper.createDatabaseIfRequired("jdbc:h2:mem:test;MODE=MySQL", "sa", "");

        assertThat(calls).isEmpty();
    }

    @Test
    void MysqlJdbcUrl缺少库名时报告明确错误() {
        var bootstrapper = new JdbcDatabaseBootstrapper((url, username, password) -> connection(new ArrayList<>()));

        assertThatThrownBy(() -> bootstrapper.createDatabaseIfRequired("jdbc:mysql://127.0.0.1:3306/", "root", "secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MySQL JDBC URL 必须包含数据库名");
    }

    @Test
    void Mysql库名只允许安全标识符() {
        var bootstrapper = new JdbcDatabaseBootstrapper((url, username, password) -> connection(new ArrayList<>()));

        assertThatThrownBy(() -> bootstrapper.createDatabaseIfRequired("jdbc:mysql://127.0.0.1:3306/matrix-code", "root", "secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MySQL 数据库名只能包含字母、数字和下划线");
    }

    private static Connection connection(List<String> calls) {
        return (Connection) Proxy.newProxyInstance(
                JdbcDatabaseBootstrapperTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "createStatement" -> statement(calls);
                    case "close" -> {
                        calls.add("connection.close");
                        yield null;
                    }
                    case "isClosed" -> false;
                    case "unwrap" -> null;
                    case "isWrapperFor" -> false;
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static Statement statement(List<String> calls) {
        return (Statement) Proxy.newProxyInstance(
                JdbcDatabaseBootstrapperTest.class.getClassLoader(),
                new Class<?>[]{Statement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "execute" -> {
                        calls.add("execute " + args[0]);
                        yield true;
                    }
                    case "close" -> {
                        calls.add("statement.close");
                        yield null;
                    }
                    case "unwrap" -> null;
                    case "isWrapperFor" -> false;
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }
}
