package com.matrixcode.persistence;

import com.matrixcode.document.domain.DocumentState;
import com.matrixcode.document.domain.DocumentType;
import com.matrixcode.document.domain.DocumentVersion;
import com.matrixcode.persistence.application.JdbcDocumentRepository;
import com.matrixcode.persistence.application.PersistenceModeProperties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcDocumentRepositoryTest {

    @Test
    void 保存后从正式文档表恢复完整版本字段() throws Exception {
        var jdbcUrl = "jdbc:h2:mem:documents_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        migrate(jdbcUrl);
        var repository = new JdbcDocumentRepository(properties(jdbcUrl));
        var frozenAt = Instant.parse("2026-06-25T08:00:00Z");
        var parent = new DocumentVersion(
                "doc-parent",
                "demo",
                DocumentType.PRD,
                "支付重构 PRD",
                "冻结需求",
                1,
                DocumentState.FROZEN,
                null,
                Instant.parse("2026-06-25T07:00:00Z"),
                "user-product",
                frozenAt
        );
        var changeDraft = new DocumentVersion(
                "doc-change",
                "demo",
                DocumentType.PRD,
                "支付重构 PRD",
                "新增退款规则",
                2,
                DocumentState.DRAFT,
                parent.id(),
                Instant.parse("2026-06-25T08:30:00Z"),
                null,
                null
        );

        repository.save(List.of(parent, changeDraft));

        var restored = repository.load();
        assertThat(restored).hasSize(2);
        assertThat(restored).anySatisfy(document -> {
            assertThat(document.id()).isEqualTo("doc-parent");
            assertThat(document.projectId()).isEqualTo("demo");
            assertThat(document.type()).isEqualTo(DocumentType.PRD);
            assertThat(document.state()).isEqualTo(DocumentState.FROZEN);
            assertThat(document.version()).isEqualTo(1);
            assertThat(document.parentVersionId()).isNull();
            assertThat(document.frozenBy()).isEqualTo("user-product");
            assertThat(document.frozenAt()).isEqualTo(frozenAt);
        });
        assertThat(restored).anySatisfy(document -> {
            assertThat(document.id()).isEqualTo("doc-change");
            assertThat(document.state()).isEqualTo(DocumentState.DRAFT);
            assertThat(document.version()).isEqualTo(2);
            assertThat(document.parentVersionId()).isEqualTo("doc-parent");
            assertThat(document.frozenBy()).isNull();
            assertThat(document.frozenAt()).isNull();
        });
        assertThat(projectCount(jdbcUrl)).isEqualTo(1);
    }

    private void migrate(String jdbcUrl) {
        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private PersistenceModeProperties properties(String jdbcUrl) {
        var properties = new PersistenceModeProperties();
        properties.setMode("jdbc");
        properties.getJdbc().setUrl(jdbcUrl);
        properties.getJdbc().setUsername("sa");
        properties.getJdbc().setPassword("");
        return properties;
    }

    private int projectCount(String jdbcUrl) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             var statement = connection.prepareStatement("select count(*) from matrixcode_projects");
             var resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
