package db.migration;

import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * 将历史项目级覆盖投影表从 {@code project_id} 主键迁移到独立 {@code id} 主键。
 *
 * <p>这两个表仍保持“每个项目一条最新投影”的业务语义；迁移后由 {@code project_id}
 * 唯一索引约束覆盖关系，表级主键统一改为 MyBatis-Plus 雪花 ID，满足后续实体
 * {@code IdType.ASSIGN_ID} 的写入策略。</p>
 */
public class V176_1__migrate_projection_tables_to_snowflake_id extends BaseJavaMigration {

    private static final DefaultIdentifierGenerator ID_GENERATOR = DefaultIdentifierGenerator.getInstance();

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        var h2 = connection.getMetaData().getDatabaseProductName().toLowerCase().contains("h2");
        migrateProjectionTable(
                connection,
                h2,
                "matrixcode_acceptance_states",
                "fk_mc_acceptance_project",
                "uk_mc_acceptance_project",
                "验收投影记录 ID，由 MyBatis-Plus 雪花算法生成，用于表级主键。"
        );
        migrateProjectionTable(
                connection,
                h2,
                "matrixcode_local_git_diff_summaries",
                "fk_mc_local_git_diff_project",
                "uk_mc_local_git_diff_project",
                "Git Diff 摘要记录 ID，由 MyBatis-Plus 雪花算法生成，用于表级主键。"
        );
    }

    private void migrateProjectionTable(
            Connection connection,
            boolean h2,
            String tableName,
            String projectForeignKeyName,
            String projectUniqueIndexName,
            String idComment
    ) throws SQLException {
        addIdColumn(connection, h2, tableName, idComment);
        fillMissingSnowflakeIds(connection, tableName);
        makeIdRequired(connection, h2, tableName, idComment);
        dropProjectForeignKey(connection, h2, tableName, projectForeignKeyName);
        switchPrimaryKeyToId(connection, tableName);
        createUniqueProjectIndex(connection, tableName, projectUniqueIndexName);
        restoreProjectForeignKey(connection, tableName, projectForeignKeyName);
    }

    private void addIdColumn(Connection connection, boolean h2, String tableName, String idComment) throws SQLException {
        if (columnExists(connection, tableName, "id")) {
            return;
        }
        if (h2) {
            execute(connection, "alter table " + tableName + " add column id varchar(64) null");
            execute(connection, "comment on column " + tableName + ".id is '" + escape(idComment) + "'");
            return;
        }
        execute(connection, "alter table " + tableName + " add column id varchar(64) null comment '" + escape(idComment) + "'");
    }

    private void fillMissingSnowflakeIds(Connection connection, String tableName) throws SQLException {
        var projectIds = new ArrayList<String>();
        try (var statement = connection.prepareStatement("""
                select project_id
                from %s
                where id is null or id = ''
                """.formatted(tableName));
             var resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                projectIds.add(resultSet.getString(1));
            }
        }

        try (var statement = connection.prepareStatement("""
                update %s
                set id = ?
                where project_id = ?
                """.formatted(tableName))) {
            for (var projectId : projectIds) {
                statement.setString(1, String.valueOf(ID_GENERATOR.nextId(null)));
                statement.setString(2, projectId);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void makeIdRequired(Connection connection, boolean h2, String tableName, String idComment) throws SQLException {
        if (h2) {
            execute(connection, "alter table " + tableName + " alter column id set not null");
            return;
        }
        execute(connection, "alter table " + tableName + " modify column id varchar(64) not null comment '" + escape(idComment) + "'");
    }

    private void switchPrimaryKeyToId(Connection connection, String tableName) throws SQLException {
        var primaryKeyColumns = primaryKeyColumns(connection, tableName);
        if (primaryKeyColumns.equals(List.of("id"))) {
            return;
        }
        execute(connection, "alter table " + tableName + " drop primary key");
        execute(connection, "alter table " + tableName + " add primary key (id)");
    }

    private void createUniqueProjectIndex(Connection connection, String tableName, String indexName) throws SQLException {
        if (indexExists(connection, tableName, indexName)) {
            return;
        }
        execute(connection, "create unique index " + indexName + " on " + tableName + " (project_id)");
    }

    private void dropProjectForeignKey(
            Connection connection,
            boolean h2,
            String tableName,
            String foreignKeyName
    ) throws SQLException {
        if (!constraintExists(connection, tableName, foreignKeyName)) {
            return;
        }
        if (h2) {
            execute(connection, "alter table " + tableName + " drop constraint " + foreignKeyName);
            return;
        }
        execute(connection, "alter table " + tableName + " drop foreign key " + foreignKeyName);
    }

    private void restoreProjectForeignKey(
            Connection connection,
            String tableName,
            String foreignKeyName
    ) throws SQLException {
        if (constraintExists(connection, tableName, foreignKeyName)) {
            return;
        }
        execute(connection, "alter table " + tableName
                + " add constraint " + foreignKeyName
                + " foreign key (project_id) references matrixcode_projects (id)");
    }

    private boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select count(*)
                from information_schema.columns
                where lower(table_name) = ? and lower(column_name) = ?
                """)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) > 0;
            }
        }
    }

    private boolean constraintExists(Connection connection, String tableName, String constraintName) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select count(*)
                from information_schema.table_constraints
                where lower(table_name) = ? and lower(constraint_name) = ?
                """)) {
            statement.setString(1, tableName);
            statement.setString(2, constraintName.toLowerCase(Locale.ROOT));
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) > 0;
            }
        }
    }

    private List<String> primaryKeyColumns(Connection connection, String tableName) throws SQLException {
        var informationSchemaColumns = primaryKeyColumnsFromInformationSchema(connection, tableName);
        if (!informationSchemaColumns.isEmpty()) {
            return informationSchemaColumns;
        }
        var columnsByPosition = new TreeMap<Short, String>();
        readPrimaryKeyColumns(connection, tableName, columnsByPosition);
        if (columnsByPosition.isEmpty()) {
            readPrimaryKeyColumns(connection, tableName.toUpperCase(Locale.ROOT), columnsByPosition);
        }
        if (columnsByPosition.isEmpty()) {
            readPrimaryKeyColumns(connection, tableName.toLowerCase(Locale.ROOT), columnsByPosition);
        }
        return new ArrayList<>(columnsByPosition.values());
    }

    private List<String> primaryKeyColumnsFromInformationSchema(Connection connection, String tableName) throws SQLException {
        var columns = new ArrayList<String>();
        try (var statement = connection.prepareStatement("""
                select lower(kcu.column_name)
                from information_schema.table_constraints tc
                join information_schema.key_column_usage kcu
                    on tc.constraint_name = kcu.constraint_name
                    and tc.table_schema = kcu.table_schema
                    and tc.table_name = kcu.table_name
                where lower(tc.table_name) = ? and lower(tc.constraint_type) = 'primary key'
                order by kcu.ordinal_position
                """)) {
            statement.setString(1, tableName);
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    columns.add(resultSet.getString(1));
                }
            }
        }
        return columns;
    }

    private void readPrimaryKeyColumns(
            Connection connection,
            String tableName,
            TreeMap<Short, String> columnsByPosition
    ) throws SQLException {
        try (var resultSet = connection.getMetaData().getPrimaryKeys(null, null, tableName)) {
            while (resultSet.next()) {
                columnsByPosition.put(
                        resultSet.getShort("KEY_SEQ"),
                        resultSet.getString("COLUMN_NAME").toLowerCase(Locale.ROOT)
                );
            }
        }
    }

    private boolean indexExists(Connection connection, String tableName, String indexName) throws SQLException {
        try (var resultSet = connection.getMetaData().getIndexInfo(null, null, tableName, false, false)) {
            while (resultSet.next()) {
                if (indexName.equalsIgnoreCase(resultSet.getString("INDEX_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String escape(String value) {
        return value.replace("'", "''");
    }
}
