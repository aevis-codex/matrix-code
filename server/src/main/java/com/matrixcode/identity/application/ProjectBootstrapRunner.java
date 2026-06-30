package com.matrixcode.identity.application;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动后执行真实库首个项目 Owner 初始化。
 *
 * <p>Runner 使用最低优先级运行，让 Flyway 和其他基础设施初始化先完成；实际幂等边界
 * 仍由 {@link ProjectIdentityService#ensureProjectOwnerWhenNoMembers(String, String, String, String, String)} 控制。</p>
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class ProjectBootstrapRunner implements ApplicationRunner {

    private final ProjectBootstrapProperties properties;
    private final ProjectIdentityService identityService;

    public ProjectBootstrapRunner(ProjectBootstrapProperties properties, ProjectIdentityService identityService) {
        this.properties = properties;
        this.identityService = identityService;
    }

    /**
     * 在显式开启配置时创建真实库首个项目 OWNER。
     *
     * <p>该 runner 只处理“项目当前没有成员”的初始化场景，不能替代成员邀请、角色变更
     * 或项目管理接口。重复启动时服务层会跳过已有成员项目，保证配置不会覆盖生产治理状态。</p>
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            return;
        }
        identityService.ensureProjectOwnerWhenNoMembers(
                properties.getProjectId(),
                properties.getProjectName(),
                properties.getOwnerUserId(),
                properties.getOwnerDisplayName(),
                properties.getCurrentStage()
        );
    }
}
