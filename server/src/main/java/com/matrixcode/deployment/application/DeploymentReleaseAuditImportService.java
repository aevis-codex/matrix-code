package com.matrixcode.deployment.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.deployment.domain.DeploymentOperationRecord;
import com.matrixcode.deployment.domain.DeploymentOperationStatus;
import com.matrixcode.deployment.domain.DeploymentOperationType;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
public class DeploymentReleaseAuditImportService {

    private final DeploymentOperationService operations;
    private final ObjectMapper mapper = new ObjectMapper();

    public DeploymentReleaseAuditImportService(DeploymentOperationService operations) {
        this.operations = operations;
    }

    /**
     * 将生产发布脚本输出的低敏 JSONL 审计行导入平台部署记录。
     *
     * <p>导入过程使用 sourceId、行号和原始行生成确定性 ID，重复导入同一审计文件不会重复写入；
     * 解析失败、空行或不支持的动作会被跳过，不阻塞同批次其他有效审计行。</p>
     */
    public DeploymentReleaseAuditImportResult importJsonLines(
            String projectId,
            String targetId,
            String actorId,
            String sourceId,
            List<String> jsonLines
    ) {
        projectId = requireText(projectId, "项目编号不能为空");
        targetId = requireText(targetId, "部署目标编号不能为空");
        actorId = requireText(actorId, "操作者不能为空");
        sourceId = requireText(sourceId, "审计来源不能为空");
        if (jsonLines == null || jsonLines.isEmpty()) {
            return new DeploymentReleaseAuditImportResult(0, 0, List.of());
        }

        var imported = new ArrayList<DeploymentOperationRecord>();
        var skipped = 0;
        for (int index = 0; index < jsonLines.size(); index++) {
            var line = jsonLines.get(index);
            var mapped = mapLine(projectId, targetId, actorId, sourceId, index + 1, line);
            if (mapped.isEmpty()) {
                skipped++;
                continue;
            }
            var record = mapped.get();
            if (operations.hasRecord(projectId, record.id())) {
                skipped++;
                continue;
            }
            imported.add(operations.recordImported(record));
        }
        return new DeploymentReleaseAuditImportResult(imported.size(), skipped, imported);
    }

    private Optional<DeploymentOperationRecord> mapLine(
            String projectId,
            String targetId,
            String actorId,
            String sourceId,
            int lineNumber,
            String line
    ) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        try {
            var node = mapper.readTree(line);
            var action = text(node, "action");
            var type = operationType(action);
            var status = operationStatus(text(node, "status"));
            var occurredAt = Instant.parse(text(node, "occurredAt"));
            if (type.isEmpty() || status.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new DeploymentOperationRecord(
                    auditId(sourceId, lineNumber, line),
                    projectId,
                    targetId,
                    actorId,
                    type.get(),
                    status.get(),
                    note(sourceId, action, status.get(), node),
                    occurredAt
            ));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private Optional<DeploymentOperationType> operationType(String action) {
        return switch (clean(action).toLowerCase()) {
            case "rollback" -> Optional.of(DeploymentOperationType.ROLLBACK);
            case "deploy", "deployment", "install" -> Optional.of(DeploymentOperationType.DEPLOYMENT);
            default -> Optional.empty();
        };
    }

    private Optional<DeploymentOperationStatus> operationStatus(String status) {
        return switch (clean(status).toUpperCase()) {
            case "RECORDED" -> Optional.of(DeploymentOperationStatus.RECORDED);
            case "SUCCEEDED", "SUCCESS" -> Optional.of(DeploymentOperationStatus.SUCCEEDED);
            case "FAILED", "FAILURE" -> Optional.of(DeploymentOperationStatus.FAILED);
            default -> Optional.empty();
        };
    }

    private String note(String sourceId, String action, DeploymentOperationStatus status, JsonNode node) {
        var parts = new ArrayList<String>();
        parts.add("发布脚本审计 " + clean(action) + " " + status.name());
        parts.add("source=" + clean(sourceId));
        append(parts, "target", text(node, "targetDir"));
        append(parts, "previous", text(node, "previousDir"));
        append(parts, "failed", text(node, "failedDir"));
        return String.join(" · ", parts);
    }

    private void append(List<String> parts, String name, String value) {
        var cleaned = clean(value);
        if (!cleaned.isBlank()) {
            parts.add(name + "=" + cleaned);
        }
    }

    private String text(JsonNode node, String fieldName) {
        var value = node == null ? null : node.get(fieldName);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private String auditId(String sourceId, int lineNumber, String line) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var raw = sourceId + "\n" + lineNumber + "\n" + line;
            var hash = HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
            return "release-audit-" + hash.substring(0, 32);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 摘要算法不可用", exception);
        }
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("[\\r\\n\\t]+", " ");
    }
}
