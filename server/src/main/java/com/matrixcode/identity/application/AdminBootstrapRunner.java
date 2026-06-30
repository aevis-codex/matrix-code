package com.matrixcode.identity.application;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动后初始化全局超级管理员账号。
 *
 * <p>该 runner 只在配置了 {@code matrixcode.auth.admin-initial-password} 时生效，
 * 用于空库或旧库升级后创建可密码登录的 admin 账号。</p>
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public class AdminBootstrapRunner implements ApplicationRunner {

    private final MatrixCodeAuthProperties authProperties;
    private final ProjectIdentityService identityService;

    public AdminBootstrapRunner(MatrixCodeAuthProperties authProperties, ProjectIdentityService identityService) {
        this.authProperties = authProperties;
        this.identityService = identityService;
    }

    /**
     * 创建全局 admin 超级管理员。
     *
     * <p>服务层会判断现有 admin 是否已有可用密码，避免生产重启反复覆盖账号密码。</p>
     */
    @Override
    public void run(ApplicationArguments args) {
        identityService.ensureInitialSuperAdmin(authProperties.getAdminInitialPassword());
    }
}
