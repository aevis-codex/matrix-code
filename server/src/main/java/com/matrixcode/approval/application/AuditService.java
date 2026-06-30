package com.matrixcode.approval.application;

import com.matrixcode.approval.domain.ApprovalDecision;
import com.matrixcode.approval.domain.AuditRecord;
import com.matrixcode.approval.domain.ToolAction;
import com.matrixcode.localexecution.application.InMemoryLocalExecutionStateStore;
import com.matrixcode.localexecution.application.LocalExecutionStateStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AuditService {

    private static final Pattern SENSITIVE_QUOTED_ARGUMENT_PATTERN =
            Pattern.compile("(?i)\\b([a-z0-9_]*(?:password|token|secret|key|apikey|credential|passphrase)[a-z0-9_]*=)([\"'])[^\"']*\\2");
    private static final Pattern SENSITIVE_ARGUMENT_PATTERN =
            Pattern.compile("(?i)\\b([a-z0-9_]*(?:password|token|secret|key|apikey|credential|passphrase)[a-z0-9_]*)=\\S+");
    private static final Pattern SENSITIVE_OPTION_WITH_QUOTED_VALUE_PATTERN =
            Pattern.compile("(?i)(--(?:token|password|secret|key|api-key|private-key|private_key|key-file|key_file|user)\\s+)([\"'])[^\"']*\\2");
    private static final Pattern SENSITIVE_OPTION_WITH_VALUE_PATTERN =
            Pattern.compile("(?i)(--(?:token|password|secret|key|api-key|private-key|private_key|key-file|key_file|user)\\s+)\\S+");
    private static final Pattern SENSITIVE_OPTION_WITH_QUOTED_EQUALS_PATTERN =
            Pattern.compile("(?i)(--(?:token|password|secret|key|api-key|private-key|private_key|key-file|key_file)=)([\"'])[^\"']*\\2");
    private static final Pattern SHORT_USER_OPTION_WITH_QUOTED_VALUE_PATTERN =
            Pattern.compile("(?i)(^|\\s)(-u\\s+)([\"'])[^\"']*\\3");
    private static final Pattern SHORT_USER_OPTION_WITH_VALUE_PATTERN =
            Pattern.compile("(?i)(^|\\s)(-u\\s+)\\S+");
    private static final Pattern SENSITIVE_OPTION_WITH_EQUALS_PATTERN =
            Pattern.compile("(?i)(--(?:token|password|secret|key|api-key|private-key|private_key|key-file|key_file)=)\\S+");
    private static final Pattern AUTHORIZATION_HEADER_PATTERN =
            Pattern.compile("(?i)(authorization:\\s*(?:bearer|token|basic)\\s+)\\S+");
    private static final Pattern API_KEY_HEADER_PATTERN =
            Pattern.compile("(?i)(x-api-key:\\s*)\\S+");
    private static final Pattern SSH_IDENTITY_FILE_PATTERN =
            Pattern.compile("(?i)(^|\\s)(-i\\s+)\\S+");

    private final List<AuditRecord> records = new ArrayList<>();
    private final LocalExecutionStateStore store;

    public AuditService() {
        this(new InMemoryLocalExecutionStateStore());
    }

    @Autowired
    public AuditService(LocalExecutionStateStore store) {
        this.store = store;
        records.addAll(store.load().auditRecords());
    }

    public synchronized AuditRecord record(ToolAction action, ApprovalDecision decision) {
        if (action == null) {
            throw new IllegalArgumentException("审计动作不能为空");
        }
        if (decision == null) {
            throw new IllegalArgumentException("审批结果不能为空");
        }
        validateAction(action);
        var record = new AuditRecord(
                UUID.randomUUID().toString(),
                action.taskId(),
                action.actorId(),
                action.toolType(),
                action.workspacePath(),
                summaryOf(action.command()),
                decision,
                Instant.now()
        );
        records.add(record);
        store.saveAuditRecords(List.copyOf(records));
        return record;
    }

    public synchronized List<AuditRecord> records() {
        return List.copyOf(records);
    }

    private void validateAction(ToolAction action) {
        requireNotBlank(action.taskId(), "任务 ID 不能为空");
        requireNotBlank(action.actorId(), "执行人不能为空");
        requireNotBlank(action.toolType(), "工具类型不能为空");
        requireNotBlank(action.command(), "命令摘要不能为空");
        requireNotBlank(action.workspacePath(), "工作区路径不能为空");
    }

    private void requireNotBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private String summaryOf(String command) {
        if (command == null) {
            return null;
        }
        var summary = SENSITIVE_QUOTED_ARGUMENT_PATTERN.matcher(command).replaceAll("$1***");
        summary = SENSITIVE_OPTION_WITH_QUOTED_EQUALS_PATTERN.matcher(summary).replaceAll("$1***");
        summary = SENSITIVE_ARGUMENT_PATTERN.matcher(summary).replaceAll("$1=***");
        summary = SENSITIVE_OPTION_WITH_EQUALS_PATTERN.matcher(summary).replaceAll("$1***");
        summary = SENSITIVE_OPTION_WITH_QUOTED_VALUE_PATTERN.matcher(summary).replaceAll("$1***");
        summary = SENSITIVE_OPTION_WITH_VALUE_PATTERN.matcher(summary).replaceAll("$1***");
        summary = SHORT_USER_OPTION_WITH_QUOTED_VALUE_PATTERN.matcher(summary).replaceAll("$1$2***");
        summary = SHORT_USER_OPTION_WITH_VALUE_PATTERN.matcher(summary).replaceAll("$1$2***");
        summary = AUTHORIZATION_HEADER_PATTERN.matcher(summary).replaceAll("$1***");
        summary = API_KEY_HEADER_PATTERN.matcher(summary).replaceAll("$1***");
        return SSH_IDENTITY_FILE_PATTERN.matcher(summary).replaceAll("$1$2***");
    }
}
