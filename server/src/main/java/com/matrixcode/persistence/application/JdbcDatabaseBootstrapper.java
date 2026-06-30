package com.matrixcode.persistence.application;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

final class JdbcDatabaseBootstrapper {

    private static final String MYSQL_PREFIX = "jdbc:mysql://";
    private final JdbcConnectionFactory connectionFactory;

    JdbcDatabaseBootstrapper() {
        this(DriverManager::getConnection);
    }

    JdbcDatabaseBootstrapper(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    void createDatabaseIfRequired(String jdbcUrl, String username, String password) {
        var target = MySqlDatabaseTarget.from(jdbcUrl);
        if (target == null) {
            return;
        }
        try (var connection = connectionFactory.connect(target.serverUrl(), username, password);
             var statement = connection.createStatement()) {
            statement.execute("create database if not exists `%s` default character set utf8mb4 collate utf8mb4_unicode_ci"
                    .formatted(target.databaseName()));
        } catch (SQLException exception) {
            throw new IllegalStateException("MySQL 自动建库失败：" + exception.getMessage(), exception);
        }
    }

    @FunctionalInterface
    interface JdbcConnectionFactory {
        Connection connect(String url, String username, String password) throws SQLException;
    }

    private record MySqlDatabaseTarget(String serverUrl, String databaseName) {

        private static MySqlDatabaseTarget from(String jdbcUrl) {
            if (jdbcUrl == null || !jdbcUrl.startsWith(MYSQL_PREFIX)) {
                return null;
            }
            var queryStart = jdbcUrl.indexOf('?');
            var prefixAndPath = queryStart < 0 ? jdbcUrl : jdbcUrl.substring(0, queryStart);
            var query = queryStart < 0 ? "" : jdbcUrl.substring(queryStart);
            var pathStart = prefixAndPath.indexOf('/', MYSQL_PREFIX.length());
            if (pathStart < 0 || pathStart == prefixAndPath.length() - 1) {
                throw new IllegalStateException("MySQL JDBC URL 必须包含数据库名");
            }
            var databaseName = prefixAndPath.substring(pathStart + 1);
            if (!databaseName.matches("[A-Za-z0-9_]+")) {
                throw new IllegalStateException("MySQL 数据库名只能包含字母、数字和下划线");
            }
            return new MySqlDatabaseTarget(prefixAndPath.substring(0, pathStart + 1) + query, databaseName);
        }
    }
}
