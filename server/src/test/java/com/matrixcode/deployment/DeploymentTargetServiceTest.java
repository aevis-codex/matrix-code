package com.matrixcode.deployment;

import com.matrixcode.deployment.application.DeploymentTargetRepository;
import com.matrixcode.deployment.application.DeploymentTargetService;
import com.matrixcode.deployment.domain.DeploymentTarget;
import com.matrixcode.deployment.domain.DeploymentTargetStatus;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeploymentTargetServiceTest {

    private final DeploymentTargetService service = new DeploymentTargetService();

    @Test
    void 配置部署目标时只保存配置不执行远程连接() {
        var target = service.configure("project-1", "测试环境", "https://test.example.com",
                "deploy@example.com", "使用 Docker Compose 部署", "https://test.example.com/health", "保留上一版本镜像");

        assertThat(target.status()).isEqualTo(DeploymentTargetStatus.RECORDED);
        assertThat(target.sshAddress()).isEqualTo("deploy@example.com");
        assertThat(target.remoteExecuted()).isFalse();
        assertThat(service.listByProject("project-1")).containsExactly(target);
    }

    @Test
    void 空环境名称会被拒绝() {
        assertThatThrownBy(() -> service.configure("project-1", " ", "https://test.example.com",
                "deploy@example.com", "部署", "https://test.example.com/health", "回滚"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("环境名称不能为空");
    }

    @Test
    void 可以按项目和目标编号查询部署目标() {
        var target = service.configure("project-1", "测试环境", "https://test.example.com",
                "deploy@example.com", "部署", "https://test.example.com/health", "回滚");

        var found = service.requireByProject("project-1", target.id());

        assertThat(found).isEqualTo(target);
    }

    @Test
    void 服务重建后恢复部署目标() {
        var store = new InMemoryWorkbenchStateStore();
        var firstService = new DeploymentTargetService(store);
        var target = firstService.configure("project-1", "测试环境", "https://test.example.com",
                "deploy@example.com", "部署", "https://test.example.com/health", "回滚");

        var secondService = new DeploymentTargetService(store);

        assertThat(secondService.listByProject("project-1")).containsExactly(target);
        assertThat(secondService.requireByProject("project-1", target.id())).isEqualTo(target);
    }

    @Test
    void 有正式仓储时部署目标不再写入工作台快照() {
        var store = new InMemoryWorkbenchStateStore();
        var repository = new RecordingDeploymentTargetRepository();
        var service = new DeploymentTargetService(store, repository);

        var target = service.configure("project-1", "测试环境", "https://test.example.com",
                "deploy@example.com", "部署", "https://test.example.com/health", "回滚");

        assertThat(repository.saved).containsExactly(target);
        assertThat(store.load().deploymentTargets()).isEmpty();
    }

    @Test
    void 正式仓储为空时从旧快照恢复并回填() {
        var store = new InMemoryWorkbenchStateStore();
        var legacy = deploymentTarget("target-legacy");
        store.saveDeploymentTargets(List.of(legacy));
        var repository = new RecordingDeploymentTargetRepository();

        var service = new DeploymentTargetService(store, repository);

        assertThat(service.listByProject("project-1")).containsExactly(legacy);
        assertThat(repository.saved).containsExactly(legacy);
    }

    @Test
    void 查询其他项目的部署目标会被拒绝() {
        var target = service.configure("project-1", "测试环境", "https://test.example.com",
                "deploy@example.com", "部署", "https://test.example.com/health", "回滚");

        assertThatThrownBy(() -> service.requireByProject("project-2", target.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("部署目标不存在");
    }

    private DeploymentTarget deploymentTarget(String id) {
        return new DeploymentTarget(
                id,
                "project-1",
                "测试环境",
                "https://test.example.com",
                "deploy@example.com",
                "部署",
                "https://test.example.com/health",
                "回滚",
                DeploymentTargetStatus.RECORDED,
                false,
                Instant.parse("2026-06-25T00:00:00Z")
        );
    }

    private static class RecordingDeploymentTargetRepository implements DeploymentTargetRepository {

        private final List<DeploymentTarget> persisted = new ArrayList<>();
        private List<DeploymentTarget> saved = List.of();

        @Override
        public List<DeploymentTarget> load() {
            return List.copyOf(persisted);
        }

        @Override
        public void save(List<DeploymentTarget> targets) {
            persisted.clear();
            persisted.addAll(targets);
            saved = List.copyOf(targets);
        }
    }
}
