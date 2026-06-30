package com.matrixcode.persistence;

import com.matrixcode.MatrixCodeServerApplication;
import com.matrixcode.document.application.DocumentRepository;
import com.matrixcode.document.domain.DocumentState;
import com.matrixcode.document.domain.DocumentType;
import com.matrixcode.document.domain.DocumentVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MybatisPlusDocumentRepositoryTest {

    private static final Instant CREATED_AT = Instant.parse("2026-06-25T07:00:00Z");
    private static final Instant FROZEN_AT = Instant.parse("2026-06-25T08:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void Jdbc模式下文档仓储使用MybatisPlus并保持正式表读写行为() throws Exception {
        var jdbcUrl = jdbcUrl("documents_mp_");

        try (var context = startJdbcContext(jdbcUrl)) {
            var repository = context.getBean(DocumentRepository.class);
            assertThat(repository.getClass().getName())
                    .contains("MybatisPlusDocumentRepository");
            var frozenPrd = document(
                    "doc-prd",
                    DocumentType.PRD,
                    "产品冻结需求",
                    "冻结后的需求正文",
                    1,
                    DocumentState.FROZEN,
                    null,
                    "user-product",
                    FROZEN_AT
            );
            var handoff = document(
                    "doc-handoff",
                    DocumentType.CODING_AGENT_HANDOFF,
                    "开发交接文档",
                    "交付结论：测试通过，可以交付",
                    1,
                    DocumentState.DRAFT,
                    frozenPrd.id(),
                    null,
                    null
            );

            repository.save(List.of(frozenPrd, handoff));

            assertThat(repository.load()).containsExactly(frozenPrd, handoff);
            assertThat(tableCount(context.getBean(DataSource.class), "matrixcode_documents")).isEqualTo(2);
            assertThat(tableCount(context.getBean(DataSource.class), "matrixcode_projects")).isEqualTo(1);
        }
    }

    @Test
    void 同Id二次保存时更新文档正文版本和冻结字段() throws Exception {
        var jdbcUrl = jdbcUrl("documents_update_mp_");

        try (var context = startJdbcContext(jdbcUrl)) {
            var repository = context.getBean(DocumentRepository.class);
            repository.save(List.of(document(
                    "doc-prd",
                    DocumentType.PRD,
                    "产品冻结需求",
                    "旧正文",
                    1,
                    DocumentState.DRAFT,
                    null,
                    null,
                    null
            )));

            var replacement = document(
                    "doc-prd",
                    DocumentType.PRD,
                    "产品冻结需求",
                    "冻结后的新正文",
                    2,
                    DocumentState.FROZEN,
                    null,
                    "user-product",
                    FROZEN_AT
            );
            repository.save(List.of(replacement));

            assertThat(repository.load()).containsExactly(replacement);
            assertThat(tableCount(context.getBean(DataSource.class), "matrixcode_documents")).isEqualTo(1);
        }
    }

    @Test
    void 默认文件模式不创建数据源和文档仓储Bean() {
        try (var context = new SpringApplicationBuilder(MatrixCodeServerApplication.class)
                .web(WebApplicationType.NONE)
                .properties(commonProperties())
                .run("--matrixcode.persistence.mode=file")) {
            assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
            assertThat(context.getBeanNamesForType(DocumentRepository.class)).isEmpty();
        }
    }

    private org.springframework.context.ConfigurableApplicationContext startJdbcContext(String jdbcUrl) {
        return new SpringApplicationBuilder(MatrixCodeServerApplication.class)
                .web(WebApplicationType.NONE)
                .properties(commonProperties())
                .run(
                        "--matrixcode.persistence.mode=jdbc",
                        "--matrixcode.persistence.jdbc.url=" + jdbcUrl,
                        "--matrixcode.persistence.jdbc.username=sa",
                        "--matrixcode.persistence.jdbc.password=",
                        "--matrixcode.persistence.jdbc.migrate-on-startup=true"
                );
    }

    private Map<String, Object> commonProperties() {
        return Map.of(
                "matrixcode.workbench-state.storage-path", tempDir.resolve("workbench-state.json").toString(),
                "matrixcode.local-execution.storage-path", tempDir.resolve("local-execution.json").toString(),
                "matrixcode.runtime-notifications.storage-path", tempDir.resolve("runtime-notifications.json").toString(),
                "spring.main.banner-mode", "off"
        );
    }

    private String jdbcUrl(String prefix) {
        return "jdbc:h2:mem:" + prefix + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
    }

    private DocumentVersion document(
            String id,
            DocumentType type,
            String title,
            String content,
            int version,
            DocumentState state,
            String parentVersionId,
            String frozenBy,
            Instant frozenAt
    ) {
        return new DocumentVersion(
                id,
                "demo",
                type,
                title,
                content,
                version,
                state,
                parentVersionId,
                CREATED_AT,
                frozenBy,
                frozenAt
        );
    }

    private int tableCount(DataSource dataSource, String tableName) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("select count(*) from " + tableName);
             var resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
