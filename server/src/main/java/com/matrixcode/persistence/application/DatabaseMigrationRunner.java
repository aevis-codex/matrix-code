package com.matrixcode.persistence.application;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 应用启动期执行数据库迁移。
 *
 * <p>该 Runner 需要在首个项目初始化等业务启动任务之前运行，确保正式表结构已经存在。
 * 是否迁移仍由持久化模式和 `migrate-on-startup` 配置控制。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DatabaseMigrationRunner implements ApplicationRunner {

    private final PersistenceModeProperties properties;
    private final DatabaseMigrationService migrationService;

    public DatabaseMigrationRunner(PersistenceModeProperties properties, DatabaseMigrationService migrationService) {
        this.properties = properties;
        this.migrationService = migrationService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if ("jdbc".equalsIgnoreCase(properties.getMode()) && properties.getJdbc().isMigrateOnStartup()) {
            migrationService.migrate();
        }
    }
}
