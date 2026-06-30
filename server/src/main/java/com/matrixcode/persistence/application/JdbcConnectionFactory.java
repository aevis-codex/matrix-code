package com.matrixcode.persistence.application;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * 为正式 JDBC 仓储集中创建连接，并对远程 MySQL 偶发通信断连做短暂重试。
 *
 * <p>MatrixCode 的正式运行环境会访问远端 MySQL。真实集成验证中发现 MySQL 握手阶段可能偶发
 * SQLState 08 系列通信异常；这类异常通常属于短暂网络抖动。统一在连接入口重试，可以避免任一
 * raw JDBC 仓储因为单次握手 EOF 直接阻断应用启动或运行中的写读动作。</p>
 */
final class JdbcConnectionFactory {

    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MILLIS = 200L;

    private JdbcConnectionFactory() {
    }

    /**
     * 使用 DriverManager 打开 JDBC 连接；通信类异常会最多重试三次。
     *
     * @param jdbc 运行时 JDBC 配置，必须包含非空 URL。
     * @return 已建立的数据库连接，调用方负责关闭。
     * @throws SQLException 连接持续失败、配置错误或重试等待被中断时抛出。
     */
    static Connection open(PersistenceModeProperties.Jdbc jdbc) throws SQLException {
        return open(jdbc, DriverManager::getConnection, JdbcConnectionFactory::sleep);
    }

    /**
     * 测试入口：允许注入连接创建器和等待器，验证重试策略而不依赖真实数据库。
     */
    static Connection open(
            PersistenceModeProperties.Jdbc jdbc,
            SqlConnector connector,
            RetrySleeper sleeper
    ) throws SQLException {
        if (jdbc.getUrl().isBlank()) {
            throw new IllegalStateException("JDBC URL 不能为空");
        }
        SQLException lastFailure = null;
        for (var attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return connector.connect(jdbc.getUrl(), jdbc.getUsername(), jdbc.getPassword());
            } catch (SQLException exception) {
                lastFailure = exception;
                if (!isCommunicationFailure(exception) || attempt == MAX_ATTEMPTS) {
                    throw exception;
                }
                pauseBeforeRetry(sleeper, attempt, exception);
            }
        }
        throw lastFailure;
    }

    /**
     * 判定是否属于 JDBC 连接通信异常；SQLState 08 是标准连接异常类别。
     */
    private static boolean isCommunicationFailure(SQLException exception) {
        var sqlState = exception.getSQLState();
        return sqlState != null && sqlState.startsWith("08");
    }

    /**
     * 每次失败后增加短暂等待，避免远程数据库刚断开时立即重连造成连续失败。
     */
    private static void pauseBeforeRetry(
            RetrySleeper sleeper,
            int attempt,
            SQLException connectionFailure
    ) throws SQLException {
        try {
            sleeper.sleep(RETRY_DELAY_MILLIS * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            var retryInterrupted = new SQLException("JDBC 连接重试被中断", "08000", interrupted);
            retryInterrupted.addSuppressed(connectionFailure);
            throw retryInterrupted;
        }
    }

    private static void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    @FunctionalInterface
    interface SqlConnector {
        Connection connect(String url, String username, String password) throws SQLException;
    }

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
