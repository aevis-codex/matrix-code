package com.matrixcode.localexecution.application;

import com.matrixcode.approval.application.ApprovalPolicy;
import com.matrixcode.approval.application.AuditService;
import com.matrixcode.approval.domain.ApprovalDecision;
import com.matrixcode.approval.domain.ToolAction;
import com.matrixcode.localexecution.domain.ExecutionTask;
import com.matrixcode.localexecution.domain.ExecutionTaskStatus;
import com.matrixcode.localexecution.domain.LocalTaskLog;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

@Service
public class LocalCommandService {

    private static final String MANUAL_APPROVAL_REJECTION_REASON = "该命令不在第五阶段可批准执行范围内";
    private static final Pattern UNSAFE_SHELL_PATTERN = Pattern.compile("(\\||>|<|&&|;|`|\\$\\(|&\\s*$|^\\s*\\w+=\\S+)");
    private static final Pattern WINDOWS_ABSOLUTE_PATH_PATTERN = Pattern.compile("^[A-Za-z]:[\\\\/].+");
    private static final Set<String> REMOTE_COMMANDS = Set.of("ssh", "scp", "sftp", "rsync");
    private static final Set<String> DEPLOYMENT_COMMANDS = Set.of(
            "deploy", "kubectl", "helm", "terraform", "ansible", "systemctl", "service",
            "docker", "docker-compose", "podman", "flyctl", "vercel", "netlify", "gcloud", "aws", "az", "doctl",
            "rollback"
    );
    private static final Set<String> DANGEROUS_LOCAL_COMMANDS = Set.of(
            "sudo", "rm", "dd", "mkfs", "shutdown", "reboot", "poweroff", "halt",
            "kill", "pkill", "chmod", "chown", "mount", "umount", "rmdir"
    );
    private static final Set<String> COMMAND_WRAPPERS = Set.of(
            "env", "bash", "sh", "zsh", "fish", "cmd", "cmd.exe", "powershell",
            "powershell.exe", "pwsh", "pwsh.exe", "python", "python3", "node", "perl", "ruby",
            "nice", "nohup", "timeout", "xargs", "command"
    );
    private static final Set<String> GIT_MUTATING_SUBCOMMANDS = Set.of(
            "clean", "reset", "checkout", "restore", "switch", "merge", "rebase",
            "commit", "push", "pull", "fetch", "tag", "branch", "rm"
    );
    private static final Set<String> BUILD_COMMANDS = Set.of(
            "mvn", "mvnw", "gradle", "gradlew", "npm", "pnpm", "yarn"
    );
    private static final Set<String> BUILD_MUTATING_ACTIONS = Set.of(
            "deploy", "publish", "release", "clean", "install"
    );

    private final WorkspaceRegistry workspaces;
    private final ApprovalPolicy approvalPolicy;
    private final AuditService auditService;
    private final LocalTaskStore taskStore;
    private final LocalTaskQueueService queueService;

    public LocalCommandService(
            WorkspaceRegistry workspaces,
            ApprovalPolicy approvalPolicy,
            AuditService auditService,
            LocalTaskStore taskStore,
            LocalTaskQueueService queueService
    ) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces 不能为空");
        this.approvalPolicy = Objects.requireNonNull(approvalPolicy, "approvalPolicy 不能为空");
        this.auditService = Objects.requireNonNull(auditService, "auditService 不能为空");
        this.taskStore = Objects.requireNonNull(taskStore, "taskStore 不能为空");
        this.queueService = Objects.requireNonNull(queueService, "queueService 不能为空");
    }

    public ExecutionTask submit(String projectId, String workspaceId, String actorId, String command) {
        command = requireText(command, "命令不能为空");
        actorId = requireText(actorId, "执行人不能为空");
        var workspace = workspaces.requireAuthorized(projectId, workspaceId);
        var taskId = UUID.randomUUID().toString();
        var action = new ToolAction(
                taskId,
                actorId,
                "SHELL",
                command,
                workspace.rootPath(),
                containsUnsafeShellSyntax(command)
        );
        var decision = approvalPolicy.decide(action);
        auditService.record(action, decision);

        if (decision == ApprovalDecision.ASK) {
            return taskStore.record(new ExecutionTask(taskId, projectId, workspaceId, actorId, "SHELL", command, decision,
                    ExecutionTaskStatus.APPROVAL_PENDING, null, "", "", 0, Instant.now()));
        }
        if (decision == ApprovalDecision.DENY) {
            return taskStore.record(new ExecutionTask(taskId, projectId, workspaceId, actorId, "SHELL", command, decision,
                    ExecutionTaskStatus.DENIED, null, "", "", 0, Instant.now()));
        }

        return queueService.enqueue(taskId, projectId, workspaceId, actorId, command, decision);
    }

    public List<ExecutionTask> recentTasks(String projectId) {
        return taskStore.recentTasks(projectId);
    }

    public List<ExecutionTask> activeTasks(String projectId) {
        return taskStore.activeTasks(projectId).stream()
                .filter(task -> task.status() == ExecutionTaskStatus.QUEUED
                        || task.status() == ExecutionTaskStatus.RUNNING)
                .toList();
    }

    public List<LocalTaskLog> recentLogs(String projectId) {
        return taskStore.recentLogs(projectId);
    }

    public List<LocalTaskLog> logsForTask(String projectId, String taskId) {
        return taskStore.logsForTask(projectId, taskId);
    }

    public ExecutionTask cancel(String projectId, String taskId, String actorId, String note) {
        var cancelActorId = requireText(actorId, "取消人不能为空");
        var canceled = queueService.cancel(projectId, taskId, cancelActorId, note);
        auditService.record(actionFor(canceled, cancelActorId, workspacePathForAudit(canceled)), ApprovalDecision.DENY);
        return canceled;
    }

    public ExecutionTask decide(String projectId, String taskId, String actorId, ApprovalDecision decision, String note) {
        var targetProjectId = requireText(projectId, "项目编号不能为空");
        var targetTaskId = requireText(taskId, "任务编号不能为空");
        var approverId = requireText(actorId, "审批人不能为空");
        if (decision != ApprovalDecision.ALLOW && decision != ApprovalDecision.DENY) {
            throw new IllegalArgumentException("审批决策只能是批准或拒绝");
        }

        var approvalNote = normalizeNote(note);
        var decidedAt = Instant.now();
        if (decision == ApprovalDecision.DENY) {
            var denied = replacePending(targetProjectId, targetTaskId, pending -> withApprovalMetadata(
                    taskWithState(pending, ApprovalDecision.DENY, ExecutionTaskStatus.DENIED,
                            null, pending.stdoutSummary(), pending.stderrSummary(), pending.durationMillis()),
                    approverId,
                    approvalNote,
                    decidedAt,
                    ""
            ));
            auditService.record(actionFor(denied, approverId, workspacePathForAudit(denied)), ApprovalDecision.DENY);
            return denied;
        }

        var pending = requirePendingTask(targetProjectId, targetTaskId);
        var workspace = workspaces.requireAuthorized(targetProjectId, pending.workspaceId());
        var rejectionReason = manualApprovalRejectionReason(pending.command());
        if (!rejectionReason.isEmpty()) {
            var rejected = replacePending(targetProjectId, targetTaskId, current -> withApprovalMetadata(
                    taskWithState(current, ApprovalDecision.DENY, ExecutionTaskStatus.DENIED,
                            null, current.stdoutSummary(), rejectionReason, current.durationMillis()),
                    approverId,
                    approvalNote,
                    decidedAt,
                    rejectionReason
            ));
            auditService.record(actionFor(rejected, approverId, workspace.rootPath()), ApprovalDecision.DENY);
            return rejected;
        }

        queueService.validateExecutionRoot(targetProjectId, pending.workspaceId(), workspace.rootPath());
        var approved = replacePending(targetProjectId, targetTaskId, current -> withApprovalMetadata(
                taskWithState(current, ApprovalDecision.ALLOW, ExecutionTaskStatus.QUEUED,
                        null, "", "", 0),
                approverId,
                approvalNote,
                decidedAt,
                ""
        ));
        auditService.record(actionFor(approved, approverId, workspace.rootPath()), ApprovalDecision.ALLOW);
        return queueService.enqueueExisting(approved, workspace.rootPath());
    }

    private List<String> tokensOf(String command) {
        return Arrays.stream(command.trim().split("\\s+"))
                .toList();
    }

    private boolean containsUnsafeShellSyntax(String command) {
        return UNSAFE_SHELL_PATTERN.matcher(command).find();
    }

    private String manualApprovalRejectionReason(String command) {
        if (containsUnsafeShellSyntax(command) || containsSensitiveArgument(command)
                || hasAbsoluteExecutablePath(command)
                || hasBlockedExecutable(command, REMOTE_COMMANDS)
                || hasBlockedExecutable(command, DEPLOYMENT_COMMANDS)
                || hasBlockedExecutable(command, DANGEROUS_LOCAL_COMMANDS)
                || hasBlockedExecutable(command, COMMAND_WRAPPERS)
                || containsDangerousSubcommand(command)) {
            return MANUAL_APPROVAL_REJECTION_REASON;
        }
        return "";
    }

    private boolean hasBlockedExecutable(String command, Set<String> blockedCommands) {
        var tokens = tokensOf(command);
        if (tokens.isEmpty()) {
            return false;
        }
        return blockedCommands.contains(executableName(tokens.getFirst()));
    }

    private boolean hasAbsoluteExecutablePath(String command) {
        var tokens = tokensOf(command);
        if (tokens.isEmpty()) {
            return false;
        }
        var executable = unquotedExecutableToken(tokens.getFirst());
        return executable.startsWith("/")
                || executable.startsWith("\\")
                || WINDOWS_ABSOLUTE_PATH_PATTERN.matcher(executable).matches();
    }

    private String executableName(String token) {
        var normalized = unquotedExecutableToken(token);
        normalized = normalized.replace('\\', '/');
        var separator = normalized.lastIndexOf('/');
        if (separator >= 0) {
            normalized = normalized.substring(separator + 1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private String unquotedExecutableToken(String token) {
        var normalized = token.trim();
        if ((normalized.startsWith("\"") && normalized.endsWith("\""))
                || (normalized.startsWith("'") && normalized.endsWith("'"))) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private boolean containsDangerousSubcommand(String command) {
        var tokens = tokensOf(command);
        if (tokens.size() < 2) {
            return false;
        }
        var executable = executableName(tokens.getFirst());
        if ("git".equals(executable)) {
            return hasAnyToken(tokens, GIT_MUTATING_SUBCOMMANDS);
        }
        if ("find".equals(executable)) {
            return tokens.stream()
                    .skip(1)
                    .map(this::normalizedToken)
                    .anyMatch(token -> token.contains("-delete"));
        }
        if (BUILD_COMMANDS.contains(executable)) {
            return hasAnyToken(tokens, BUILD_MUTATING_ACTIONS);
        }
        return false;
    }

    private boolean hasAnyToken(List<String> tokens, Set<String> blockedTokens) {
        return tokens.stream()
                .skip(1)
                .map(this::normalizedToken)
                .anyMatch(blockedTokens::contains);
    }

    private String normalizedToken(String token) {
        return unquotedExecutableToken(token).toLowerCase(Locale.ROOT);
    }

    private boolean containsSensitiveArgument(String command) {
        for (var token : tokensOf(command)) {
            var normalized = token.toLowerCase(Locale.ROOT);
            if (normalized.contains("password")
                    || normalized.contains("token")
                    || normalized.contains("secret")
                    || normalized.contains("apikey")
                    || normalized.contains("api-key")
                    || normalized.contains("api_key")
                    || normalized.contains("credential")
                    || normalized.contains("passphrase")
                    || normalized.contains("private-key")
                    || normalized.contains("private_key")
                    || normalized.contains("key-file")
                    || normalized.contains("key_file")
                    || normalized.equals("--key")
                    || normalized.startsWith("--key=")
                    || normalized.equals("key")
                    || normalized.startsWith("key=")
                    || normalized.equals("--user")
                    || normalized.startsWith("--user=")
                    || normalized.equals("-u")
                    || normalized.equals("-i")) {
                return true;
            }
        }
        return false;
    }

    private ExecutionTask findTask(String projectId, String taskId) {
        try {
            return taskStore.require(projectId, taskId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("待审批任务不存在");
        }
    }

    private ExecutionTask requirePendingTask(String projectId, String taskId) {
        var task = findTask(projectId, taskId);
        if (task.status() != ExecutionTaskStatus.APPROVAL_PENDING) {
            throw new IllegalArgumentException("任务已完成审批，不能重复处理");
        }
        return task;
    }

    private ExecutionTask replacePending(
            String projectId,
            String taskId,
            Function<ExecutionTask, ExecutionTask> replacementFactory
    ) {
        try {
            return taskStore.replace(projectId, taskId, existing -> {
                if (existing.status() != ExecutionTaskStatus.APPROVAL_PENDING) {
                    throw new IllegalArgumentException("任务已完成审批，不能重复处理");
                }
                return replacementFactory.apply(existing);
            });
        } catch (IllegalArgumentException exception) {
            if ("执行任务不存在".equals(exception.getMessage())) {
                throw new IllegalArgumentException("待审批任务不存在");
            }
            throw exception;
        }
    }

    private ExecutionTask withApprovalMetadata(
            ExecutionTask task,
            String approverId,
            String approvalNote,
            Instant decidedAt,
            String safetyRejectionReason
    ) {
        return new ExecutionTask(
                task.taskId(),
                task.projectId(),
                task.workspaceId(),
                task.actorId(),
                task.toolType(),
                task.command(),
                task.approvalDecision(),
                task.status(),
                task.exitCode(),
                task.stdoutSummary(),
                task.stderrSummary(),
                task.durationMillis(),
                task.createdAt(),
                approverId,
                approvalNote,
                decidedAt,
                safetyRejectionReason
        );
    }

    private ExecutionTask taskWithState(
            ExecutionTask task,
            ApprovalDecision approvalDecision,
            ExecutionTaskStatus status,
            Integer exitCode,
            String stdoutSummary,
            String stderrSummary,
            long durationMillis
    ) {
        return new ExecutionTask(
                task.taskId(),
                task.projectId(),
                task.workspaceId(),
                task.actorId(),
                task.toolType(),
                task.command(),
                approvalDecision,
                status,
                exitCode,
                stdoutSummary,
                stderrSummary,
                durationMillis,
                task.createdAt()
        );
    }

    private ToolAction actionFor(ExecutionTask task, String actorId, String workspacePath) {
        return new ToolAction(
                task.taskId(),
                actorId,
                task.toolType(),
                task.command(),
                workspacePath,
                containsUnsafeShellSyntax(task.command())
        );
    }

    private String workspacePathForAudit(ExecutionTask task) {
        try {
            return workspaces.requireAuthorized(task.projectId(), task.workspaceId()).rootPath();
        } catch (IllegalArgumentException exception) {
            return "工作区未授权：" + task.workspaceId();
        }
    }

    private String normalizeNote(String note) {
        return note == null ? "" : note.trim();
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
