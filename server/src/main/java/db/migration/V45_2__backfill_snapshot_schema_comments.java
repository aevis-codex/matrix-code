package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 为早期按需创建的 JDBC JSON 快照表补齐注释。
 *
 * <p>`matrixcode_state_snapshots` 不是 Flyway 创建的领域表，而是早期 JDBC 快照仓储在首次读写时
 * 自动创建的过渡表。真实库中该表可能已经存在，因此需要用新的迁移版本补齐表注释和字段注释；
 * 新环境后续由仓储建表路径继续负责写入同样的注释。</p>
 */
public class V45_2__backfill_snapshot_schema_comments extends BaseJavaMigration {

    private static final String TABLE_NAME = "matrixcode_state_snapshots";
    private static final String TABLE_COMMENT = "保存工作台 JSON 快照过渡数据，用于在领域表迁移期间保留尚未迁出的状态切片。";
    private static final Map<String, String> COLUMN_COMMENTS = orderedMap(
            "slice_key", "快照切片键，例如 workbench-state，用于区分不同状态投影。",
            "version", "快照结构版本号，用于读取时做兼容判断和迁移。",
            "payload", "快照 JSON 正文，保存该切片的完整序列化状态。",
            "updated_at", "快照最后写入时间，用于排查状态恢复和同步问题。"
    );

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        var databaseProduct = connection.getMetaData().getDatabaseProductName().toLowerCase();
        if (!tableExists(connection, databaseProduct)) {
            return;
        }
        applyTableComment(connection, databaseProduct);
        for (var columnComment : COLUMN_COMMENTS.entrySet()) {
            applyColumnComment(connection, databaseProduct, columnComment.getKey(), columnComment.getValue());
        }
    }

    /**
     * 判断默认快照表是否存在。该迁移只补历史已存在表，不在 Flyway 中强制创建过渡表。
     */
    private boolean tableExists(Connection connection, String databaseProduct) throws SQLException {
        var sql = databaseProduct.contains("mysql")
                ? """
                select count(*)
                from information_schema.tables
                where table_schema = database() and table_name = ?
                """
                : """
                select count(*)
                from information_schema.tables
                where lower(table_name) = ?
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, databaseProduct.contains("mysql") ? TABLE_NAME : TABLE_NAME.toLowerCase());
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) == 1;
            }
        }
    }

    /**
     * 按数据库方言写入表级注释。
     */
    private void applyTableComment(Connection connection, String databaseProduct) throws SQLException {
        var sql = databaseProduct.contains("mysql")
                ? "alter table " + quoteIdentifier(TABLE_NAME) + " comment = '" + escapeSql(TABLE_COMMENT) + "'"
                : "comment on table " + TABLE_NAME + " is '" + escapeSql(TABLE_COMMENT) + "'";
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * 按数据库方言写入字段注释。MySQL 修改字段注释时必须保留原字段定义。
     */
    private void applyColumnComment(
            Connection connection,
            String databaseProduct,
            String columnName,
            String comment
    ) throws SQLException {
        var sql = databaseProduct.contains("mysql")
                ? "alter table " + quoteIdentifier(TABLE_NAME) + " modify column "
                + mysqlColumnDefinition(connection, columnName, comment)
                : "comment on column " + TABLE_NAME + "." + columnName + " is '" + escapeSql(comment) + "'";
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * 从 MySQL 元数据恢复完整字段定义，避免写入注释时改变类型、默认值、空值约束或 extra 属性。
     */
    private String mysqlColumnDefinition(Connection connection, String columnName, String comment) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select column_type, is_nullable, column_default, extra
                from information_schema.columns
                where table_schema = database()
                  and table_name = ?
                  and column_name = ?
                """)) {
            statement.setString(1, TABLE_NAME);
            statement.setString(2, columnName);
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("未找到字段定义：" + TABLE_NAME + "." + columnName);
                }
                var definition = new StringBuilder()
                        .append(quoteIdentifier(columnName))
                        .append(' ')
                        .append(resultSet.getString("column_type"));
                definition.append("NO".equalsIgnoreCase(resultSet.getString("is_nullable")) ? " not null" : " null");
                var defaultValue = resultSet.getString("column_default");
                if (defaultValue != null) {
                    definition.append(" default ").append(mysqlDefault(defaultValue));
                }
                var extra = resultSet.getString("extra");
                if (extra != null && !extra.isBlank()) {
                    definition.append(' ').append(extra);
                }
                definition.append(" comment '").append(escapeSql(comment)).append('\'');
                return definition.toString();
            }
        }
    }

    private static String mysqlDefault(String defaultValue) {
        var upper = defaultValue.toUpperCase();
        if (upper.contains("CURRENT_TIMESTAMP") || upper.equals("NULL") || upper.startsWith("B'")) {
            return defaultValue;
        }
        return "'" + escapeSql(defaultValue) + "'";
    }

    private static String quoteIdentifier(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    private static String escapeSql(String value) {
        return value.replace("'", "''");
    }

    private static Map<String, String> orderedMap(String... pairs) {
        var map = new LinkedHashMap<String, String>();
        for (var index = 0; index < pairs.length; index += 2) {
            map.put(pairs[index], pairs[index + 1]);
        }
        return Map.copyOf(map);
    }
}
