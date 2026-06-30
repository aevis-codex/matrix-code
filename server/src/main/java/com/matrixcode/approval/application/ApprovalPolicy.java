package com.matrixcode.approval.application;

import com.matrixcode.approval.domain.ApprovalDecision;
import com.matrixcode.approval.domain.ToolAction;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
public class ApprovalPolicy {

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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean isSafeLocalTestCommand(ToolAction action) {
        if (!"SHELL".equalsIgnoreCase(action.toolType())) {
            return false;
        }
        return SAFE_LOCAL_COMMANDS.contains(tokensOf(action.command()));
    }

    private List<String> tokensOf(String command) {
        return Arrays.stream(command.trim().split("\\s+"))
                .toList();
    }
}
