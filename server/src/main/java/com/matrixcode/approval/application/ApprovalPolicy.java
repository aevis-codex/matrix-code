package com.matrixcode.approval.application;

import com.matrixcode.approval.domain.ApprovalDecision;
import com.matrixcode.approval.domain.ToolAction;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class ApprovalPolicy {

    private static final Set<String> APPROVAL_MODES = Set.of("ask", "auto", "yolo");
    private static final List<List<String>> SAFE_LOCAL_COMMANDS = List.of(
            List.of("/Users/Masons/Ai/Maven/bin/mvn", "-Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store", "test"),
            List.of("/Users/Masons/Ai/Maven/bin/mvn", "-Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store", "-q", "test"),
            List.of("/Users/Masons/Ai/Maven/bin/mvn", "-Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store", "-q", "-pl", "server", "test"),
            List.of("npm", "test"),
            List.of("pnpm", "test"),
            List.of("yarn", "test"),
            List.of("./gradlew", "test"),
            List.of("gradle", "test")
    );

    public ApprovalDecision decide(ToolAction action) {
        if (action == null) {
            return ApprovalDecision.DENY;
        }
        if (isBlank(action.taskId()) || isBlank(action.actorId()) || isBlank(action.toolType())
                || isBlank(action.command()) || isBlank(action.workspacePath())) {
            return ApprovalDecision.DENY;
        }
        if (action.dangerous()) {
            return ApprovalDecision.ASK;
        }
        if ("SSH".equalsIgnoreCase(action.toolType())) {
            return ApprovalDecision.ASK;
        }
        if (isSafeLocalTestCommand(action)) {
            return ApprovalDecision.ALLOW;
        }
        return ApprovalDecision.ASK;
    }

    /**
     * 按 Agent Composer 的工具权限模式裁剪本地工具审批结果。
     *
     * <p>作用域：工具执行边界；场景：问询模式强制人工确认，自动模式沿用安全策略，
     * Yolo 模式只放行非危险本地 Shell 动作，仍不允许绕过危险命令、远程 SSH 或非法请求。</p>
     */
    public ApprovalDecision decide(ToolAction action, String approvalMode) {
        var baseDecision = decide(action);
        if (baseDecision == ApprovalDecision.DENY || action == null) {
            return baseDecision;
        }
        var mode = normalizedApprovalMode(approvalMode);
        if ("ask".equals(mode)) {
            return ApprovalDecision.ASK;
        }
        if ("yolo".equals(mode) && isNonDangerousLocalShell(action)) {
            return ApprovalDecision.ALLOW;
        }
        return baseDecision;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean isSafeLocalTestCommand(ToolAction action) {
        if (!"SHELL".equalsIgnoreCase(action.toolType())) {
            return false;
        }
        return SAFE_LOCAL_COMMANDS.contains(tokensOf(action.command()));
    }

    private boolean isNonDangerousLocalShell(ToolAction action) {
        return "SHELL".equalsIgnoreCase(action.toolType())
                && !action.dangerous()
                && !containsHighRiskShellToken(action.command())
                && !containsRemoteShellToken(action.command());
    }

    private boolean containsRemoteShellToken(String command) {
        var normalized = normalizedShellCommand(command);
        return normalized.contains("ssh") || normalized.contains("scp") || normalized.contains("rsync");
    }

    private boolean containsHighRiskShellToken(String command) {
        var normalized = normalizedShellCommand(command);
        return normalized.matches(".*(^|\\s)(sudo\\s+)?(/[\\w./-]+/)?rm(\\s+|$).*")
                || normalized.contains("git clean")
                || normalized.contains("find . -delete")
                || normalized.contains("mvn deploy")
                || normalized.contains("kubectl ")
                || normalized.contains("docker compose up")
                || normalized.contains("docker-compose up")
                || normalized.contains("helm rollback")
                || normalized.contains("terraform destroy")
                || normalized.contains("ansible-playbook")
                || normalized.contains("--private-key")
                || normalized.contains("--key-file")
                || normalized.contains("password=")
                || normalized.contains(" token=")
                || normalized.contains("secret=")
                || normalized.contains("api_key")
                || normalized.contains("authorization:");
    }

    private String normalizedShellCommand(String command) {
        return command == null
                ? ""
                : command.toLowerCase(Locale.ROOT)
                .replace("'", "")
                .replace("\"", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String normalizedApprovalMode(String approvalMode) {
        var normalized = approvalMode == null || approvalMode.isBlank()
                ? "auto"
                : approvalMode.trim().toLowerCase(Locale.ROOT);
        return APPROVAL_MODES.contains(normalized) ? normalized : "ask";
    }

    private List<String> tokensOf(String command) {
        return Arrays.stream(command.trim().split("\\s+"))
                .toList();
    }
}
