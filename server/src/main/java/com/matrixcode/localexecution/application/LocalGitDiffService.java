package com.matrixcode.localexecution.application;

import com.matrixcode.localexecution.domain.GitDiffSummary;
import com.matrixcode.workbench.application.InMemoryWorkbenchStateStore;
import com.matrixcode.workbench.application.WorkbenchStateStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class LocalGitDiffService {

    private final WorkspaceRegistry workspaces;
    private final Map<String, GitDiffSummary> latestByProject = new ConcurrentHashMap<>();
    private final WorkbenchStateStore stateStore;
    private final Optional<LocalWorkspaceActivityRepository> activityRepository;

    public LocalGitDiffService(WorkspaceRegistry workspaces) {
        this(workspaces, new InMemoryWorkbenchStateStore(), Optional.empty());
    }

    public LocalGitDiffService(WorkspaceRegistry workspaces, WorkbenchStateStore stateStore) {
        this(workspaces, stateStore, Optional.empty());
    }

    @Autowired
    public LocalGitDiffService(
            WorkspaceRegistry workspaces,
            WorkbenchStateStore stateStore,
            Optional<LocalWorkspaceActivityRepository> activityRepository
    ) {
        this.workspaces = workspaces;
        this.stateStore = stateStore;
        this.activityRepository = activityRepository == null ? Optional.empty() : activityRepository;
        latestByProject.putAll(restoreSummaries());
    }

    public GitDiffSummary capture(String projectId, String workspaceId) {
        var workspace = workspaces.requireAuthorized(projectId, workspaceId);
        var root = Path.of(workspace.rootPath());
        if (!Files.exists(root.resolve(".git"))) {
            var summary = new GitDiffSummary(projectId, workspaceId, false, List.of(), "", Instant.now());
            latestByProject.put(projectId, summary);
            saveSummaries();
            return summary;
        }
        var changedFiles = linesOf(run(root, "git", "diff", "--name-only"));
        var stat = run(root, "git", "diff", "--stat").trim();
        var summary = new GitDiffSummary(projectId, workspaceId, true, changedFiles, stat, Instant.now());
        latestByProject.put(projectId, summary);
        saveSummaries();
        return summary;
    }

    public GitDiffSummary latest(String projectId) {
        return latestByProject.get(projectId);
    }

    private String run(Path root, String... command) {
        try {
            var process = new ProcessBuilder(command)
                    .directory(root.toFile())
                    .start();
            var finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalArgumentException("Git diff 采集超时");
            }
            var stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            var stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new IllegalArgumentException("Git diff 采集失败：" + stderr.trim());
            }
            return stdout;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Git diff 命令无法启动");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Git diff 采集被中断");
        }
    }

    private List<String> linesOf(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        return Arrays.stream(output.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
    }

    /**
     * 加载最近 Git Diff 摘要。
     *
     * <p>JDBC 模式优先读取正式表；正式表为空时从旧 `workbench-state.gitDiffSummaries`
     * 回填，保证历史工作台状态可以平滑迁移。</p>
     */
    private Map<String, GitDiffSummary> restoreSummaries() {
        if (activityRepository.isEmpty()) {
            return stateStore.load().gitDiffSummaries();
        }
        var formal = activityRepository.get().loadGitDiffSummaries();
        if (!formal.isEmpty()) {
            return formal;
        }
        var legacy = stateStore.load().gitDiffSummaries();
        if (!legacy.isEmpty()) {
            activityRepository.get().saveGitDiffSummaries(legacy);
        }
        return legacy;
    }

    /**
     * 保存最近 Git Diff 摘要。
     *
     * <p>JDBC 模式只写正式表；文件模式继续写原 `WorkbenchStateStore`。</p>
     */
    private void saveSummaries() {
        var snapshot = Map.copyOf(latestByProject);
        if (activityRepository.isPresent()) {
            activityRepository.get().saveGitDiffSummaries(snapshot);
            return;
        }
        stateStore.saveGitDiffSummaries(snapshot);
    }
}
