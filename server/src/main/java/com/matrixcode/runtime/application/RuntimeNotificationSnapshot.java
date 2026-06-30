package com.matrixcode.runtime.application;

import com.matrixcode.runtime.domain.RuntimeNotification;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record RuntimeNotificationSnapshot(
        int version,
        Map<String, List<RuntimeNotification>> projects,
        Map<String, Map<String, Map<String, Instant>>> readReceipts
) {
    public RuntimeNotificationSnapshot(int version, Map<String, List<RuntimeNotification>> projects) {
        this(version, projects, Map.of());
    }

    public RuntimeNotificationSnapshot {
        projects = projects == null ? Map.of() : Map.copyOf(projects);
        readReceipts = copyReadReceipts(readReceipts);
    }

    public static RuntimeNotificationSnapshot empty() {
        return new RuntimeNotificationSnapshot(1, Map.of(), Map.of());
    }

    private static Map<String, Map<String, Map<String, Instant>>> copyReadReceipts(
            Map<String, Map<String, Map<String, Instant>>> source
    ) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        var projects = new HashMap<String, Map<String, Map<String, Instant>>>();
        source.forEach((projectId, users) -> {
            if (projectId == null || projectId.isBlank() || users == null || users.isEmpty()) {
                return;
            }
            var copiedUsers = users.entrySet().stream()
                    .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
                    .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
                    .collect(Collectors.toUnmodifiableMap(
                            entry -> entry.getKey().trim(),
                            entry -> entry.getValue().entrySet().stream()
                                    .filter(read -> read.getKey() != null && !read.getKey().isBlank())
                                    .filter(read -> read.getValue() != null)
                                    .collect(Collectors.toUnmodifiableMap(
                                            read -> read.getKey().trim(),
                                            Map.Entry::getValue
                                    ))
                    ));
            if (!copiedUsers.isEmpty()) {
                projects.put(projectId.trim(), copiedUsers);
            }
        });
        return Map.copyOf(projects);
    }
}
