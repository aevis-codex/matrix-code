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
 * 将早期 JSON 快照兼容表从 {@code slice_key} 主键迁移到独立 {@code id} 主键。
 *
 * <p>该表仍按 {@code slice_key} 覆盖同一状态切片；迁移后由唯一索引保持覆盖语义，
 * 表级主键统一改为雪花 ID，满足全库主键策略。</p>
 */
public class V176_2__migrate_snapshot_table_to_snowflake_id extends BaseJavaMigration {

    private static final String TABLE_NAME = "matrixcode_state_snapshots";
    private static final String SLICE_KEY_INDEX = "uk_mc_state_snapshots_slice_key";
    private static final String ID_COMMENT = "快照记录 ID，由 MyBatis-Plus 雪花算法生成，用于表级主键。";
    private static final DefaultIdentifierGenerator ID_GENERATOR = DefaultIdentifierGenerator.getInstance();

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        if (!tableExists(connection)) {
            return;
        }
        var h2 = connection.getMetaData().getDatabaseProductName().toLowerCase().contains("h2");
        addIdColumn(connection, h2);
        fillMissingSnowflakeIds(connection);
        makeIdRequired(connection, h2);
        switchPrimaryKeyToId(connection);
        createUniqueSliceKeyIndex(connection);
    }

    private boolean tableExists(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select count(*)
                from information_schema.tables
                where lower(table_name) = ?
                """)) {
            statement.setString(1, TABLE_NAME);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) > 0;
            }
        }
    }

    private void addIdColumn(Connection connection, boolean h2) throws SQLException {
        if (columnExists(connection, "id")) {
            return;
        }
        if (h2) {
            execute(connection, "alter table " + TABLE_NAME + " add column id varchar(64) null");
            execute(connection, "comment on column " + TABLE_NAME + ".id is '" + escape(ID_COMMENT) + "'");
            return;
        }
        execute(connection, "alter table " + TABLE_NAME + " add column id varchar(64) null comment '" + escape(ID_COMMENT) + "'");
    }

    private void fillMissingSnowflakeIds(Connection connection) throws SQLException {
        var sliceKeys = new ArrayList<String>();
        try (var statement = connection.prepareStatement("""
                select slice_key
                from %s
                where id is null or id = ''
                """.formatted(TABLE_NAME));
             var resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                sliceKeys.add(resultSet.getString(1));
            }
        }

        try (var statement = connection.prepareStatement("""
                update %s
                set id = ?
                where slice_key = ?
                """.formatted(TABLE_NAME))) {
            for (var sliceKey : sliceKeys) {
                statement.setString(1, String.valueOf(ID_GENERATOR.nextId(null)));
                statement.setString(2, sliceKey);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void makeIdRequired(Connection connection, boolean h2) throws SQLException {
        if (h2) {
            execute(connection, "alter table " + TABLE_NAME + " alter column id set not null");
            return;
        }
        execute(connection, "alter table " + TABLE_NAME
                + " modify column id varchar(64) not null comment '" + escape(ID_COMMENT) + "'");
    }

    private void switchPrimaryKeyToId(Connection connection) throws SQLException {
        var primaryKeyColumns = primaryKeyColumns(connection);
        if (primaryKeyColumns.equals(List.of("id"))) {
            return;
        }
        execute(connection, "alter table " + TABLE_NAME + " drop primary key");
        execute(connection, "alter table " + TABLE_NAME + " add primary key (id)");
    }

    private void createUniqueSliceKeyIndex(Connection connection) throws SQLException {
        if (indexExists(connection, SLICE_KEY_INDEX)) {
            return;
        }
        execute(connection, "create unique index " + SLICE_KEY_INDEX + " on " + TABLE_NAME + " (slice_key)");
    }

    private boolean columnExists(Connection connection, String columnName) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select count(*)
                from information_schema.columns
                where lower(table_name) = ? and lower(column_name) = ?
                """)) {
            statement.setString(1, TABLE_NAME);
            statement.setString(2, columnName);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) > 0;
            }
        }
    }

    private List<String> primaryKeyColumns(Connection connection) throws SQLException {
        var informationSchemaColumns = primaryKeyColumnsFromInformationSchema(connection);
        if (!informationSchemaColumns.isEmpty()) {
            return informationSchemaColumns;
        }
        var columnsByPosition = new TreeMap<Short, String>();
        readPrimaryKeyColumns(connection, TABLE_NAME, columnsByPosition);
        if (columnsByPosition.isEmpty()) {
            readPrimaryKeyColumns(connection, TABLE_NAME.toUpperCase(Locale.ROOT), columnsByPosition);
        }
        if (columnsByPosition.isEmpty()) {
            readPrimaryKeyColumns(connection, TABLE_NAME.toLowerCase(Locale.ROOT), columnsByPosition);
        }
        return new ArrayList<>(columnsByPosition.values());
    }

    private List<String> primaryKeyColumnsFromInformationSchema(Connection connection) throws SQLException {
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
            statement.setString(1, TABLE_NAME);
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

    private boolean indexExists(Connection connection, String indexName) throws SQLException {
        try (var resultSet = connection.getMetaData().getIndexInfo(null, null, TABLE_NAME, false, false)) {
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
