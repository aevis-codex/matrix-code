package com.matrixcode.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseMigrationCommentPolicyTest {

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "create\\s+table\\s+([a-zA-Z0-9_]+)\\s*\\((.*?)\\)\\s*comment\\s*=\\s*'[^']+'",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern VERSIONED_MIGRATION = Pattern.compile("V(\\d+)_\\d+__.+\\.sql");

    @Test
    void 第42阶段后的建表迁移必须包含表注释和字段注释() throws Exception {
        var migrationDirectory = Path.of("src/main/resources/db/migration");
        var migrationFiles = Files.list(migrationDirectory)
                .filter(path -> path.getFileName().toString().endsWith(".sql"))
                .filter(path -> migrationVersion(path) >= 42)
                .toList();

        assertThat(migrationFiles)
                .as("第 42 阶段应包含带注释的 Agent 运行表迁移")
                .isNotEmpty();

        for (var migrationFile : migrationFiles) {
            var sql = Files.readString(migrationFile);
            var matcher = CREATE_TABLE.matcher(sql);
            var createTableCount = 0;
            while (matcher.find()) {
                createTableCount++;
                assertColumnComments(migrationFile, matcher.group(1), matcher.group(2));
            }

            if (sql.toLowerCase(Locale.ROOT).contains("create table")) {
                assertThat(createTableCount)
                        .as("%s 的 create table 必须带表级 comment", migrationFile.getFileName())
                        .isGreaterThan(0);
            }
        }
    }

    private static int migrationVersion(Path path) {
        var matcher = VERSIONED_MIGRATION.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            return -1;
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static void assertColumnComments(Path migrationFile, String tableName, String columnBlock) throws IOException {
        var missingComments = Arrays.stream(columnBlock.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .filter(line -> !line.startsWith("primary key"))
                .filter(line -> !line.startsWith("constraint "))
                .filter(line -> !line.startsWith("unique "))
                .filter(line -> !line.startsWith("foreign key"))
                .filter(line -> !line.toLowerCase(Locale.ROOT).contains(" comment "))
                .toList();

        assertThat(missingComments)
                .as("%s 中 %s 的普通字段必须带 comment", migrationFile.getFileName(), tableName)
                .isEmpty();
    }
}
