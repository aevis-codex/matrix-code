package com.matrixcode.persistence.application;

import com.matrixcode.document.application.DocumentRepository;
import com.matrixcode.document.domain.DocumentState;
import com.matrixcode.document.domain.DocumentType;
import com.matrixcode.document.domain.DocumentVersion;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class JdbcDocumentRepository implements DocumentRepository {

    private final PersistenceModeProperties properties;
    private final DatabaseMigrationService migrationService;
    private boolean migrated;

    public JdbcDocumentRepository(
            PersistenceModeProperties properties,
            DatabaseMigrationService migrationService
    ) {
        this.properties = properties;
        this.migrationService = migrationService;
    }

    public JdbcDocumentRepository(PersistenceModeProperties properties) {
        this.properties = properties;
        this.migrationService = null;
    }

    @Override
    public List<DocumentVersion> load() {
        ensureSchema();
        var documents = new ArrayList<DocumentVersion>();
        try (var connection = connection();
             var statement = connection.prepareStatement("""
                     select id, project_id, document_type, title, content, version, status,
                            parent_version_id, created_at, frozen_by, frozen_at
                     from matrixcode_documents
                     order by project_id, title, version, id
                     """);
             var resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                documents.add(readDocument(resultSet));
            }
            return documents;
        } catch (SQLException exception) {
            throw new IllegalStateException("文档表读取失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public void save(List<DocumentVersion> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        ensureSchema();
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                for (var document : documents) {
                    ensureProject(connection, document.projectId());
                    if (updateDocument(connection, document) == 0) {
                        insertDocument(connection, document);
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("文档表写入失败：" + exception.getMessage(), exception);
        }
    }

    private DocumentVersion readDocument(ResultSet resultSet) throws SQLException {
        return new DocumentVersion(
                resultSet.getString("id"),
                resultSet.getString("project_id"),
                DocumentType.valueOf(resultSet.getString("document_type")),
                resultSet.getString("title"),
                resultSet.getString("content"),
                resultSet.getInt("version"),
                DocumentState.valueOf(resultSet.getString("status")),
                resultSet.getString("parent_version_id"),
                instant(resultSet.getTimestamp("created_at")),
                resultSet.getString("frozen_by"),
                optionalInstant(resultSet.getTimestamp("frozen_at"))
        );
    }

    private int updateDocument(Connection connection, DocumentVersion document) throws SQLException {
        try (var statement = connection.prepareStatement("""
                update matrixcode_documents
                set document_type = ?, title = ?, status = ?, version = ?, frozen = ?, content = ?,
                    parent_version_id = ?, updated_by_role = ?, updated_at = ?, frozen_by = ?, frozen_at = ?
                where id = ?
                """)) {
            bindMutableDocumentFields(statement, document);
            statement.setString(12, document.id());
            return statement.executeUpdate();
        }
    }

    private void insertDocument(Connection connection, DocumentVersion document) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into matrixcode_documents
                    (id, project_id, document_type, title, status, version, frozen, content,
                     created_by_role, updated_by_role, created_at, updated_at, parent_version_id, frozen_by, frozen_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, document.id());
            statement.setString(2, document.projectId());
            statement.setString(3, document.type().name());
            statement.setString(4, document.title());
            statement.setString(5, document.state().name());
            statement.setInt(6, document.version());
            statement.setBoolean(7, document.state() == DocumentState.FROZEN);
            statement.setString(8, document.content());
            statement.setString(9, null);
            statement.setString(10, document.frozenBy());
            statement.setTimestamp(11, timestamp(document.createdAt()));
            statement.setTimestamp(12, timestamp(updatedAt(document)));
            statement.setString(13, document.parentVersionId());
            statement.setString(14, document.frozenBy());
            statement.setTimestamp(15, optionalTimestamp(document.frozenAt()));
            statement.executeUpdate();
        }
    }

    private void bindMutableDocumentFields(java.sql.PreparedStatement statement, DocumentVersion document)
            throws SQLException {
        statement.setString(1, document.type().name());
        statement.setString(2, document.title());
        statement.setString(3, document.state().name());
        statement.setInt(4, document.version());
        statement.setBoolean(5, document.state() == DocumentState.FROZEN);
        statement.setString(6, document.content());
        statement.setString(7, document.parentVersionId());
        statement.setString(8, document.frozenBy());
        statement.setTimestamp(9, timestamp(updatedAt(document)));
        statement.setString(10, document.frozenBy());
        statement.setTimestamp(11, optionalTimestamp(document.frozenAt()));
    }

    private void ensureProject(Connection connection, String projectId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                update matrixcode_projects
                set updated_at = CURRENT_TIMESTAMP
                where id = ?
                """)) {
            statement.setString(1, projectId);
            if (statement.executeUpdate() > 0) {
                return;
            }
        }
        try (var statement = connection.prepareStatement("""
                insert into matrixcode_projects
                    (id, name, description, owner_user_id, status, current_stage, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """)) {
            statement.setString(1, projectId);
            statement.setString(2, projectId);
            statement.setString(3, "");
            statement.setString(4, null);
            statement.setString(5, "ACTIVE");
            statement.setString(6, "文档中心");
            statement.executeUpdate();
        }
    }

    private Connection connection() throws SQLException {
        var jdbc = properties.getJdbc();
        if (jdbc.getUrl().isBlank()) {
            throw new IllegalStateException("JDBC URL 不能为空");
        }
        return JdbcConnectionFactory.open(jdbc);
    }

    private synchronized void ensureSchema() {
        if (migrated || migrationService == null || !properties.getJdbc().isMigrateOnStartup()) {
            return;
        }
        migrationService.migrate();
        migrated = true;
    }

    private Instant updatedAt(DocumentVersion document) {
        return document.frozenAt() == null ? document.createdAt() : document.frozenAt();
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant == null ? Instant.EPOCH : instant);
    }

    private Timestamp optionalTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? Instant.EPOCH : timestamp.toInstant();
    }

    private Instant optionalInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
