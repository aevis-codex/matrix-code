package com.matrixcode.deployment;

import com.matrixcode.deployment.application.DeploymentOperationService;
import com.matrixcode.deployment.application.DeploymentReleaseAuditImportService;
import com.matrixcode.deployment.application.DeploymentTargetService;
import com.matrixcode.deployment.domain.DeploymentOperationStatus;
import com.matrixcode.deployment.domain.DeploymentOperationType;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeploymentOperationServiceTest {

    private final DeploymentTargetService targets = new DeploymentTargetService();
    private final DeploymentOperationService service = new DeploymentOperationService(targets);

    @Test
    void 可以记录部署和回滚操作并按目标查询最新记录() {
        var target = targets.configure("demo", "预发环境", "https://pre.example.com",
                "deploy@example.com", "部署", "https://pre.example.com/health", "回滚");

        var deployment = service.record("demo", target.id(), "user-ops",
                DeploymentOperationType.DEPLOYMENT, DeploymentOperationStatus.SUCCEEDED, "按发布单完成预发部署");
        var rollback = service.record("demo", target.id(), "user-ops",
                DeploymentOperationType.ROLLBACK, DeploymentOperationStatus.RECORDED, "记录回滚方案");

        assertThat(deployment.projectId()).isEqualTo("demo");
        assertThat(deployment.targetId()).isEqualTo(target.id());
        assertThat(deployment.actorId()).isEqualTo("user-ops");
        assertThat(deployment.type()).isEqualTo(DeploymentOperationType.DEPLOYMENT);
        assertThat(deployment.status()).isEqualTo(DeploymentOperationStatus.SUCCEEDED);
        assertThat(deployment.note()).isEqualTo("按发布单完成预发部署");
        assertThat(deployment.createdAt()).isNotNull();
        assertThat(rollback.type()).isEqualTo(DeploymentOperationType.ROLLBACK);
        assertThat(service.latestDeploymentForTarget("demo", target.id())).isEqualTo(deployment);
        assertThat(service.latestRollbackForTarget("demo", target.id())).isEqualTo(rollback);
        assertThat(service.latestByProject("demo")).containsExactly(rollback, deployment);
    }

    @Test
    void 说明为空会被拒绝() {
        var target = targets.configure("demo", "预发环境", "https://pre.example.com",
                "deploy@example.com", "部署", "https://pre.example.com/health", "回滚");

        assertThatThrownBy(() -> service.record("demo", target.id(), "user-ops",
                DeploymentOperationType.DEPLOYMENT, DeploymentOperationStatus.SUCCEEDED, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("操作说明不能为空");
    }

    @Test
    void 其他项目不能记录该目标操作() {
        var target = targets.configure("demo", "预发环境", "https://pre.example.com",
                "deploy@example.com", "部署", "https://pre.example.com/health", "回滚");

        assertThatThrownBy(() -> service.record("other", target.id(), "user-ops",
                DeploymentOperationType.DEPLOYMENT, DeploymentOperationStatus.SUCCEEDED, "部署完成"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("部署目标不存在");
    }

    @Test
    void 服务重建后恢复部署操作历史() {
        var store = new InMemoryWorkbenchStateStore();
        var firstTargets = new DeploymentTargetService(store);
        var firstService = new DeploymentOperationService(firstTargets, store);
        var target = firstTargets.configure("demo", "预发环境", "https://pre.example.com",
                "deploy@example.com", "部署", "https://pre.example.com/health", "回滚");
        var deployment = firstService.record("demo", target.id(), "user-ops",
                DeploymentOperationType.DEPLOYMENT, DeploymentOperationStatus.SUCCEEDED, "部署完成");

        var secondTargets = new DeploymentTargetService(store);
        var secondService = new DeploymentOperationService(secondTargets, store);

        assertThat(secondService.latestByProject("demo")).containsExactly(deployment);
        assertThat(secondService.latestDeploymentForTarget("demo", target.id())).isEqualTo(deployment);
    }

    @Test
    void 每个项目最多保留最近二十条操作记录() {
        var target = targets.configure("demo", "预发环境", "https://pre.example.com",
                "deploy@example.com", "部署", "https://pre.example.com/health", "回滚");

        for (int index = 1; index <= 21; index++) {
            service.record("demo", target.id(), "user-ops",
                    DeploymentOperationType.DEPLOYMENT, DeploymentOperationStatus.SUCCEEDED, "部署记录 " + index);
        }

        assertThat(service.latestByProject("demo"))
                .hasSize(20)
                .extracting("note")
                .containsExactly(
                        "部署记录 21",
                        "部署记录 20",
                        "部署记录 19",
                        "部署记录 18",
                        "部署记录 17",
                        "部署记录 16",
                        "部署记录 15",
                        "部署记录 14",
                        "部署记录 13",
                        "部署记录 12",
                        "部署记录 11",
                        "部署记录 10",
                        "部署记录 9",
                        "部署记录 8",
                        "部署记录 7",
                        "部署记录 6",
                        "部署记录 5",
                        "部署记录 4",
                        "部署记录 3",
                        "部署记录 2"
                );
    }

    @Test
    void 可以导入生产发布脚本审计并保持幂等() {
        var target = targets.configure("demo", "生产环境", "https://matrixcode.example.com",
                "deploy@example.com", "部署", "https://matrixcode.example.com/actuator/health", "回滚上一版本");
        var importer = new DeploymentReleaseAuditImportService(service);
        var auditLines = List.of("""
                {"action":"rollback","status":"SUCCEEDED","occurredAt":"2026-06-29T09:00:00Z","previousDir":"/opt/matrixcode.previous.20260629170000","targetDir":"/opt/matrixcode","failedDir":"/opt/matrixcode.failed.20260629170100"}
                """);

        var first = importer.importJsonLines("demo", target.id(), "user-ops", "rollback-audit.jsonl", auditLines);
        var second = importer.importJsonLines("demo", target.id(), "user-ops", "rollback-audit.jsonl", auditLines);

        assertThat(first.importedCount()).isEqualTo(1);
        assertThat(first.skippedCount()).isZero();
        assertThat(second.importedCount()).isZero();
        assertThat(second.skippedCount()).isEqualTo(1);
        assertThat(service.latestByProject("demo")).hasSize(1);
        assertThat(service.latestRollbackForTarget("demo", target.id()))
                .satisfies(record -> {
                    assertThat(record.type()).isEqualTo(DeploymentOperationType.ROLLBACK);
                    assertThat(record.status()).isEqualTo(DeploymentOperationStatus.SUCCEEDED);
                    assertThat(record.createdAt()).isEqualTo(Instant.parse("2026-06-29T09:00:00Z"));
                    assertThat(record.note()).contains("发布脚本审计 rollback SUCCEEDED");
                    assertThat(record.note()).contains("target=/opt/matrixcode");
                    assertThat(record.note()).doesNotContain("MATRIXCODE_");
                });
    }

    @Test
    void 导入发布脚本审计会跳过非法行和不支持动作() {
        var target = targets.configure("demo", "生产环境", "https://matrixcode.example.com",
                "deploy@example.com", "部署", "https://matrixcode.example.com/actuator/health", "回滚上一版本");
        var importer = new DeploymentReleaseAuditImportService(service);

        var result = importer.importJsonLines("demo", target.id(), "user-ops", "rollback-audit.jsonl", List.of(
                "",
                "not-json",
                "{\"action\":\"unknown\",\"status\":\"SUCCEEDED\",\"occurredAt\":\"2026-06-29T09:00:00Z\"}",
                "{\"action\":\"rollback\",\"status\":\"SUCCEEDED\",\"occurredAt\":\"2026-06-29T09:00:00Z\",\"targetDir\":\"/opt/matrixcode\"}"
        ));

        assertThat(result.importedCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isEqualTo(3);
        assertThat(service.latestRollbackForTarget("demo", target.id())).isNotNull();
    }
}
