package com.matrixcode.approval;

import com.matrixcode.approval.application.ApprovalPolicy;
import com.matrixcode.approval.application.AuditService;
import com.matrixcode.approval.domain.ApprovalDecision;
import com.matrixcode.approval.domain.AuditRecord;
import com.matrixcode.approval.domain.ToolAction;
import com.matrixcode.localexecution.application.InMemoryLocalExecutionStateStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class ApprovalPolicyTest {

    private final ApprovalPolicy policy = new ApprovalPolicy();

    @Test
    void 明确安全的本地测试命令可以自动执行() {
        var commands = List.of(
                "/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store test",
                "/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q test",
                "/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test",
                "npm test",
                "pnpm test",
                "yarn test",
                "./gradlew test",
                "gradle test"
        );

        assertThat(commands)
                .allSatisfy(command -> {
                    var action = new ToolAction("task-1", "user-dev", "SHELL", command, "/repo/payment", false);
                    assertThat(policy.decide(action)).as(command).isEqualTo(ApprovalDecision.ALLOW);
                });
    }

    @Test
    void 裸Maven命令必须人工确认() {
        var commands = List.of(
                "mvn test",
                "mvn -q test",
                "mvn -q -pl server test",
                "/tmp/apache-maven-3.9.10/bin/mvn -q -pl server test",
                "/Users/Masons/Ai/Maven/bin/mvn -q -pl server test"
        );

        assertThat(commands)
                .allSatisfy(command -> {
                    var action = new ToolAction("task-1", "user-dev", "SHELL", command, "/repo/payment", false);
                    assertThat(policy.decide(action)).as(command).isEqualTo(ApprovalDecision.ASK);
                });
    }

    @Test
    void 标记危险的动作必须人工确认() {
        var action = new ToolAction("task-1", "user-dev", "SHELL", "mvn deploy", "/repo/payment", true);

        assertThat(policy.decide(action)).isEqualTo(ApprovalDecision.ASK);
    }

    @Test
    void ssh动作必须人工确认() {
        var action = new ToolAction("task-1", "user-ops", "SSH", "systemctl restart payment", "/repo/payment", false);

        assertThat(policy.decide(action)).isEqualTo(ApprovalDecision.ASK);
    }

    @Test
    void 问询模式会把原本可自动执行的安全命令降级为人工确认() {
        var action = new ToolAction(
                "task-1",
                "user-dev",
                "SHELL",
                "/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store test",
                "/repo/payment",
                false
        );

        assertThat(policy.decide(action, "ask")).isEqualTo(ApprovalDecision.ASK);
    }

    @Test
    void Yolo模式只放行非危险本地Shell命令且不会绕过危险和远程动作() {
        var ordinaryShell = new ToolAction("task-1", "user-dev", "SHELL", "npm run lint", "/repo/payment", false);
        var dangerousShell = new ToolAction("task-2", "user-dev", "SHELL", "rm -rf build", "/repo/payment", true);
        var sshAction = new ToolAction("task-3", "user-ops", "SSH", "systemctl restart payment", "/repo/payment", false);
        var invalidAction = new ToolAction("task-4", "user-dev", "SHELL", "  ", "/repo/payment", false);
        var unmarkedDestructiveShell = new ToolAction("task-5", "user-dev", "SHELL", "rm -rf build", "/repo/payment", false);

        assertThat(policy.decide(ordinaryShell, "yolo")).isEqualTo(ApprovalDecision.ALLOW);
        assertThat(policy.decide(dangerousShell, "yolo")).isEqualTo(ApprovalDecision.ASK);
        assertThat(policy.decide(sshAction, "yolo")).isEqualTo(ApprovalDecision.ASK);
        assertThat(policy.decide(invalidAction, "yolo")).isEqualTo(ApprovalDecision.DENY);
        assertThat(policy.decide(unmarkedDestructiveShell, "yolo")).isEqualTo(ApprovalDecision.ASK);
    }

    @Test
    void 删除动作必须人工确认() {
        var commands = List.of(
                "rm file",
                "rm -rf /repo/payment/build",
                "rm -fr /repo/payment/build",
                "rm -r -f /repo/payment/build",
                "rm    -rf /repo/payment/build",
                "rm -Rf /repo/payment/build",
                "rm --recursive /repo/payment/build",
                "rm --force /repo/payment/build",
                "sudo rm -rf /repo/payment/build",
                "/bin/rm -rf /repo/payment/build",
                "sh -c \"rm -rf /repo/payment/build\"",
                "bash -lc 'rm -rf /repo/payment/build'",
                "r'm' -rf /repo/payment/build",
                "r\"m\" -rf /repo/payment/build"
        );

        assertThat(commands)
                .allSatisfy(command -> {
                    var action = new ToolAction("task-1", "user-dev", "SHELL", command, "/repo/payment", false);
                    assertThat(policy.decide(action)).as(command).isEqualTo(ApprovalDecision.ASK);
                });
    }

    @Test
    void shell中的远程动作必须人工确认() {
        var commands = List.of(
                "ssh host systemctl restart app",
                "s's'h host",
                "sudo ssh host",
                "/usr/bin/ssh host",
                "scp file host:/tmp",
                "rsync -e ssh ./dist host:/tmp/dist",
                "rsync ./dist host:/tmp/dist",
                "rsync --rsh=ssh ./dist host:/tmp/dist"
        );

        assertThat(commands)
                .allSatisfy(command -> {
                    var action = new ToolAction("task-1", "user-dev", "SHELL", command, "/repo/payment", false);
                    assertThat(policy.decide(action)).as(command).isEqualTo(ApprovalDecision.ASK);
                });
    }

    @Test
    void 部署回滚和凭证动作必须人工确认() {
        var commands = List.of(
                "mvn deploy",
                "kubectl rollout undo deployment/app",
                "docker compose up -d",
                "docker-compose up -d",
                "git clean -fdx",
                "find . -delete",
                "helm rollback app 1",
                "curl --api-key sk_test https://example.com",
                "OPENAI_API_KEY=sk-test mvn test",
                "kubectl apply -f deploy.yaml",
                "terraform destroy",
                "ansible-playbook deploy.yml --private-key ~/.ssh/id_rsa",
                "deploy --key-file /tmp/prod.key"
        );

        assertThat(commands)
                .allSatisfy(command -> {
                    var action = new ToolAction("task-1", "user-dev", "SHELL", command, "/repo/payment", false);
                    assertThat(policy.decide(action)).as(command).isEqualTo(ApprovalDecision.ASK);
                });
    }

    @Test
    void 空命令或空工作区会被拒绝() {
        var emptyCommand = new ToolAction("task-1", "user-dev", "SHELL", "  ", "/repo/payment", false);
        var emptyWorkspace = new ToolAction("task-1", "user-dev", "SHELL", "/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store test", "", false);

        assertThat(policy.decide(emptyCommand)).isEqualTo(ApprovalDecision.DENY);
        assertThat(policy.decide(emptyWorkspace)).isEqualTo(ApprovalDecision.DENY);
    }

    @Test
    void 无效字段优先拒绝即使动作被标记危险() {
        var emptyCommand = new ToolAction("task-1", "user-dev", "SHELL", "  ", "/repo/payment", true);
        var emptyWorkspace = new ToolAction("task-1", "user-dev", "SHELL", "rm -rf build", "", true);

        assertThat(policy.decide(emptyCommand)).isEqualTo(ApprovalDecision.DENY);
        assertThat(policy.decide(emptyWorkspace)).isEqualTo(ApprovalDecision.DENY);
    }

    @Test
    void 审计记录会保留动作字段和审批结果() {
        var service = new AuditService();
        var action = new ToolAction("task-1", "user-dev", "SHELL", "/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store test", "/repo/payment", false);

        var record = service.record(action, ApprovalDecision.ALLOW);

        assertThat(record.id()).isNotBlank();
        assertThat(record.taskId()).isEqualTo("task-1");
        assertThat(record.actorId()).isEqualTo("user-dev");
        assertThat(record.toolType()).isEqualTo("SHELL");
        assertThat(record.workspacePath()).isEqualTo("/repo/payment");
        assertThat(record.summary()).isEqualTo("/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store test");
        assertThat(record.decision()).isEqualTo(ApprovalDecision.ALLOW);
        assertThat(record.occurredAt()).isNotNull();
    }

    @Test
    void 审计摘要会脱敏常见敏感参数() {
        var service = new AuditService();
        var action = new ToolAction(
                "task-1",
                "user-dev",
                "SHELL",
                "deploy password=123 token=abc secret=value key=private --token abc --password 123 API_KEY=value Authorization: Bearer abc ssh -i ~/.ssh/id_rsa PRIVATE_KEY=abc AWS_SECRET_ACCESS_KEY=foo --api-key bar apiKey=qux Authorization: token raw X-API-Key: value Authorization: Basic abc credential=value credentials=value passphrase=value --user user:pass -u user:pass --password \"abc def\" password=\"abc def\" --password=\"ghi jkl\" --api-key=\"quoted value\" credential='foo bar' key=\"private key\"",
                "/repo/payment",
                false
        );

        var record = service.record(action, ApprovalDecision.ASK);

        assertThat(record.summary())
                .isEqualTo("deploy password=*** token=*** secret=*** key=*** --token *** --password *** API_KEY=*** Authorization: Bearer *** ssh -i *** PRIVATE_KEY=*** AWS_SECRET_ACCESS_KEY=*** --api-key *** apiKey=*** Authorization: token *** X-API-Key: *** Authorization: Basic *** credential=*** credentials=*** passphrase=*** --user *** -u *** --password *** password=*** --password=*** --api-key=*** credential=*** key=***")
                .doesNotContain("123", "abc", "value", "private", "~/.ssh/id_rsa", "foo", "bar", "qux", "raw", "user:pass", "abc def", "ghi jkl", "quoted value", "foo bar", "private key");
    }

    @Test
    void 审计摘要会脱敏下划线形式密钥文件参数() {
        var service = new AuditService();
        var privateKeyAction = new ToolAction(
                "task-1",
                "user-dev",
                "SHELL",
                "deploy --private_key /tmp/prod.key",
                "/repo/payment",
                false
        );
        var keyFileAction = new ToolAction(
                "task-2",
                "user-dev",
                "SHELL",
                "deploy --key_file /tmp/prod.key",
                "/repo/payment",
                false
        );

        var privateKeyRecord = service.record(privateKeyAction, ApprovalDecision.ASK);
        var keyFileRecord = service.record(keyFileAction, ApprovalDecision.ASK);

        assertThat(privateKeyRecord.summary()).isEqualTo("deploy --private_key ***");
        assertThat(keyFileRecord.summary()).isEqualTo("deploy --key_file ***");
    }

    @Test
    void 审计记录拒绝空动作和空审批结果() {
        var service = new AuditService();
        var action = new ToolAction("task-1", "user-dev", "SHELL", "/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store test", "/repo/payment", false);
        var emptyWorkspace = new ToolAction("task-1", "user-dev", "SHELL", "/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store test", "  ", false);
        var emptyCommand = new ToolAction("task-1", "user-dev", "SHELL", "  ", "/repo/payment", false);

        assertThatThrownBy(() -> service.record(null, ApprovalDecision.DENY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("审计动作不能为空");
        assertThatThrownBy(() -> service.record(action, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("审批结果不能为空");
        assertThatThrownBy(() -> service.record(emptyWorkspace, ApprovalDecision.ALLOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工作区路径不能为空");
        assertThatThrownBy(() -> service.record(emptyCommand, ApprovalDecision.ALLOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("命令摘要不能为空");
    }

    @Test
    void 审计记录按写入顺序返回() {
        var service = new AuditService();
        var first = new ToolAction("task-1", "user-dev", "SHELL", "/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store test", "/repo/payment", false);
        var second = new ToolAction("task-2", "user-ops", "SSH", "systemctl restart payment", "/repo/payment", true);

        service.record(first, ApprovalDecision.ALLOW);
        service.record(second, ApprovalDecision.ASK);

        assertThat(service.records())
                .extracting(AuditRecord::taskId, AuditRecord::decision)
                .containsExactly(
                        tuple("task-1", ApprovalDecision.ALLOW),
                        tuple("task-2", ApprovalDecision.ASK)
                );
    }

    @Test
    void 重建服务后恢复审计记录() {
        var store = new InMemoryLocalExecutionStateStore();
        var service = new AuditService(store);
        service.record(new ToolAction("task-1", "user-ops", "SHELL", "git status", "/repo", false), ApprovalDecision.ASK);

        var restored = new AuditService(store);

        assertThat(restored.records()).hasSize(1);
        assertThat(restored.records().getFirst().summary()).isEqualTo("git status");
    }

    @Test
    void 审计记录列表不能被外部修改() {
        var service = new AuditService();
        var action = new ToolAction("task-1", "user-dev", "SHELL", "/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store test", "/repo/payment", false);
        service.record(action, ApprovalDecision.ALLOW);

        assertThatThrownBy(() -> service.records().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(service.records()).hasSize(1);
    }
}
