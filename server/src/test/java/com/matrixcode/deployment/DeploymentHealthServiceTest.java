package com.matrixcode.deployment;

import com.matrixcode.deployment.application.DeploymentHealthProbe;
import com.matrixcode.deployment.application.DeploymentHealthService;
import com.matrixcode.deployment.application.DeploymentTargetService;
import com.matrixcode.deployment.domain.DeploymentHealthStatus;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeploymentHealthServiceTest {

    private final DeploymentTargetService targets = new DeploymentTargetService();

    @Test
    void 健康检查会使用部署目标保存的健康检查地址并记录结果() {
        var target = targets.configure("demo", "预发环境", "https://pre.example.com",
                "deploy@example.com", "部署", "https://pre.example.com/health", "回滚");
        var service = new DeploymentHealthService(targets, uri -> {
            assertThat(uri.toString()).isEqualTo("https://pre.example.com/health");
            return new DeploymentHealthProbe(DeploymentHealthStatus.HEALTHY, 204, 18, "HTTP 204");
        });

        var check = service.check("demo", target.id(), "user-ops");

        assertThat(check.projectId()).isEqualTo("demo");
        assertThat(check.targetId()).isEqualTo(target.id());
        assertThat(check.actorId()).isEqualTo("user-ops");
        assertThat(check.status()).isEqualTo(DeploymentHealthStatus.HEALTHY);
        assertThat(check.httpStatus()).isEqualTo(204);
        assertThat(check.durationMillis()).isEqualTo(18);
        assertThat(check.summary()).isEqualTo("HTTP 204");
        assertThat(check.checkedAt()).isNotNull();
        assertThat(service.latestByProject("demo")).containsExactly(check);
        assertThat(service.latestForTarget("demo", target.id())).isEqualTo(check);
    }

    @Test
    void 非Http协议会记录为不可达且不会调用客户端() {
        var target = targets.configure("demo", "预发环境", "https://pre.example.com",
                "deploy@example.com", "部署", "ssh://pre.example.com/health", "回滚");
        var service = new DeploymentHealthService(targets, uri -> {
            throw new AssertionError("不应调用 HTTP 客户端");
        });

        var check = service.check("demo", target.id(), "user-ops");

        assertThat(check.status()).isEqualTo(DeploymentHealthStatus.UNREACHABLE);
        assertThat(check.httpStatus()).isNull();
        assertThat(check.summary()).contains("健康检查地址协议不支持");
    }

    @Test
    void 空操作者会被拒绝() {
        var target = targets.configure("demo", "预发环境", "https://pre.example.com",
                "deploy@example.com", "部署", "https://pre.example.com/health", "回滚");
        var service = new DeploymentHealthService(targets,
                uri -> new DeploymentHealthProbe(DeploymentHealthStatus.HEALTHY, 200, 3, "HTTP 200"));

        assertThatThrownBy(() -> service.check("demo", target.id(), " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("操作者不能为空");
    }

    @Test
    void 服务重建后恢复健康检查历史且不重新调用客户端() {
        var store = new InMemoryWorkbenchStateStore();
        var firstTargets = new DeploymentTargetService(store);
        var target = firstTargets.configure("demo", "预发环境", "https://pre.example.com",
                "deploy@example.com", "部署", "https://pre.example.com/health", "回滚");
        var firstService = new DeploymentHealthService(firstTargets,
                uri -> new DeploymentHealthProbe(DeploymentHealthStatus.HEALTHY, 200, 3, "HTTP 200"), store);
        var check = firstService.check("demo", target.id(), "user-ops");

        var secondTargets = new DeploymentTargetService(store);
        var secondService = new DeploymentHealthService(secondTargets, uri -> {
            throw new AssertionError("恢复历史不应调用健康检查客户端");
        }, store);

        assertThat(secondService.latestByProject("demo")).containsExactly(check);
        assertThat(secondService.latestForTarget("demo", target.id())).isEqualTo(check);
    }
}
