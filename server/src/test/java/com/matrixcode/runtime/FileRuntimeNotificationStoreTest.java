package com.matrixcode.runtime;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.matrixcode.runtime.application.FileRuntimeNotificationStore;
import com.matrixcode.runtime.application.RuntimeNotificationSnapshot;
import com.matrixcode.runtime.application.RuntimeNotificationStorageProperties;
import com.matrixcode.runtime.domain.RuntimeNotification;
import com.matrixcode.runtime.domain.RuntimeNotificationLevel;
import com.matrixcode.runtime.domain.RuntimeNotificationSourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileRuntimeNotificationStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void 文件不存在时加载空快照() {
        var store = store(tempDir.resolve("missing/runtime-notifications.json"));

        var snapshot = store.load();

        assertThat(snapshot.version()).isEqualTo(1);
        assertThat(snapshot.projects()).isEmpty();
    }

    @Test
    void 保存后可以重新加载提醒和已读时间() {
        var path = tempDir.resolve("runtime/runtime-notifications.json");
        var store = store(path);
        var readAt = Instant.parse("2026-06-25T08:00:00Z");
        var notification = notification("approval:task-1", readAt);

        store.save(new RuntimeNotificationSnapshot(1, Map.of("demo", List.of(notification))));

        var loaded = store(path).load();
        assertThat(Files.exists(path)).isTrue();
        assertThat(loaded.projects()).containsKey("demo");
        assertThat(loaded.projects().get("demo").getFirst().id()).isEqualTo("approval:task-1");
        assertThat(loaded.projects().get("demo").getFirst().readAt()).isEqualTo(readAt);
    }

    @Test
    void 文件损坏时加载空快照且保留原文件() throws Exception {
        var path = tempDir.resolve("runtime/runtime-notifications.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{broken");

        var loaded = store(path).load();

        assertThat(loaded.projects()).isEmpty();
        assertThat(Files.readString(path)).isEqualTo("{broken");
    }

    private FileRuntimeNotificationStore store(Path path) {
        var properties = new RuntimeNotificationStorageProperties();
        properties.setStoragePath(path);
        var mapper = JsonMapper.builder()
                .findAndAddModules()
                .build();
        return new FileRuntimeNotificationStore(mapper, properties);
    }

    private RuntimeNotification notification(String id, Instant readAt) {
        return new RuntimeNotification(
                id,
                "demo",
                RuntimeNotificationLevel.ACTION,
                "需要审批本地命令",
                "git status",
                RuntimeNotificationSourceType.APPROVAL,
                "task-1",
                Instant.parse("2026-06-25T07:59:00Z"),
                readAt
        );
    }
}
