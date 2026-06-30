package com.matrixcode.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MybatisPlusIdPolicyTest {

    @Test
    void Id主键实体默认使用MyBatisPlus雪花算法() throws IOException {
        var entityDir = sourcePath("src/main/java/com/matrixcode/persistence/mybatis/entity");
        var invalidEntities = Files.list(entityDir)
                .filter(path -> path.getFileName().toString().endsWith(".java"))
                .filter(path -> read(path).contains("@TableId(value = \"id\""))
                .filter(path -> !read(path).contains("@TableId(value = \"id\", type = IdType.ASSIGN_ID)"))
                .map(path -> path.getFileName().toString())
                .sorted()
                .toList();

        assertThat(invalidEntities)
                .as("所有名为 id 的 MyBatis-Plus 主键字段必须使用 ASSIGN_ID，避免新增记录退回手工主键")
                .isEmpty();
    }

    @Test
    void 记录仍需独立迁移的业务投影主键() throws IOException {
        var entityDir = sourcePath("src/main/java/com/matrixcode/persistence/mybatis/entity");
        var projectKeyEntities = Files.list(entityDir)
                .filter(path -> path.getFileName().toString().endsWith(".java"))
                .filter(path -> read(path).contains("@TableId(value = \"project_id\""))
                .map(path -> path.getFileName().toString())
                .sorted()
                .toList();

        assertThat(projectKeyEntities)
                .as("这两个表是按项目覆盖的投影表，改为雪花 id 需要独立 Flyway 数据迁移")
                .isEqualTo(List.of("AcceptanceStateEntity.java", "LocalGitDiffSummaryEntity.java"));
    }

    private Path sourcePath(String relativePath) {
        var modulePath = Path.of(relativePath);
        if (Files.exists(modulePath)) {
            return modulePath;
        }
        return Path.of("server").resolve(relativePath);
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("读取源码失败：" + path, exception);
        }
    }
}
