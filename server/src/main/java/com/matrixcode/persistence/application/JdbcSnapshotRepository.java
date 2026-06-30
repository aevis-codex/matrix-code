package com.matrixcode.persistence.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
@ConditionalOnProperty(prefix = "matrixcode.persistence", name = "mode", havingValue = "jdbc")
public class JdbcSnapshotRepository {

    private static final String TABLE_COMMENT = "保存工作台 JSON 快照过渡数据，用于在领域表迁移期间保留尚未迁出的状态切片。";

    private static final Map<String, String> COLUMN_COMMENTS = orderedMap(
            "slice_key", "快照切片键，例如 workbench-state，用于区分不同状态投影。",
            "version", "快照结构版本号，用于读取时做兼容判断和迁移。",
            "payload", "快照 JSON 正文，保存该切片的完整序列化状态。",
            "updated_at", "快照最后写入时间，用于排查状态恢复和同步问题。"
    );

    private final PersistenceModeProperties properties;

    public JdbcSnapshotRepository(PersistenceModeProperties properties) {
        this.properties = properties;
    }

    public Optional<StoredSnapshot> load(String sliceKey) {
        try (var connection = connection()) {
            ensureTable(connection);
            var sql = "select slice_key, version, payload from " + tableName() + " where slice_key = ?";
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, sliceKey);
                try (var resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new StoredSnapshot(
                            resultSet.getString("slice_key"),
                            resultSet.getInt("version"),
                            resultSet.getString("payload")
                    ));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("JDBC 快照读取失败：" + exception.getMessage(), exception);
        }
    }

    public void save(String sliceKey, int version, String payload) {
        try (var connection = connection()) {
            ensureTable(connection);
            if (update(connection, sliceKey, version, payload) == 0) {
                insert(connection, sliceKey, version, payload);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("JDBC 快照写入失败：" + exception.getMessage(), exception);
        }
    }

    private int update(Connection connection, String sliceKey, int version, String payload) throws SQLException {
        var sql = "update " + tableName()
                + " set version = ?, payload = ?, updated_at = CURRENT_TIMESTAMP where slice_key = ?";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setInt(1, version);
            statement.setString(2, payload);
            statement.setString(3, sliceKey);
            return statement.executeUpdate();
        }
    }

    private void insert(Connection connection, String sliceKey, int version, String payload) throws SQLException {
        var sql = "insert into " + tableName()
                + " (slice_key, version, payload, updated_at) values (?, ?, ?, CURRENT_TIMESTAMP)";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, sliceKey);
            statement.setInt(2, version);
            statement.setString(3, payload);
            statement.executeUpdate();
        }
    }

    private void ensureTable(Connection connection) throws SQLException {
        var sql = "create table if not exists " + tableName() + " ("
                + "slice_key varchar(80) primary key, "
                + "version integer not null, "
                + "payload text not null, "
                + "updated_at timestamp not null)";
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
        applySchemaCommentsIfMissing(connection);
    }

    /**
     * 为按需创建的快照表补齐表和字段注释。
     *
     * <p>该表是早期 JSON 快照过渡层，不由 Flyway 建表；因此仓储建表路径自身必须补注释，
     * 避免新环境在首次写入快照后生成无注释的正式表。</p>
     */
    private void applySchemaCommentsIfMissing(Connection connection) throws SQLException {
        var databaseProduct = connection.getMetaData().getDatabaseProductName().toLowerCase();
        if (!hasMissingSchemaComments(connection, databaseProduct)) {
            return;
        }
        applyTableComment(connection, databaseProduct);
        for (var columnComment : COLUMN_COMMENTS.entrySet()) {
            applyColumnComment(connection, databaseProduct, columnComment.getKey(), columnComment.getValue());
        }
    }

    /**
     * 读取数据库元数据，判断当前快照表是否仍存在空注释。
     */
    private boolean hasMissingSchemaComments(Connection connection, String databaseProduct) throws SQLException {
        if (isBlank(readTableComment(connection, databaseProduct))) {
            return true;
        }
        for (var columnName : COLUMN_COMMENTS.keySet()) {
            if (isBlank(readColumnComment(connection, databaseProduct, columnName))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按数据库方言读取表注释。MySQL 使用 table_comment，H2 使用 remarks。
     */
    private String readTableComment(Connection connection, String databaseProduct) throws SQLException {
        var sql = databaseProduct.contains("mysql")
                ? """
                select table_comment
                from information_schema.tables
                where table_schema = database() and table_name = ?
                """
                : """
                select remarks
                from information_schema.tables
                where lower(table_name) = ?
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizedTableName(databaseProduct));
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    /**
     * 按数据库方言读取字段注释。MySQL 使用 column_comment，H2 使用 remarks。
     */
    private String readColumnComment(Connection connection, String databaseProduct, String columnName) throws SQLException {
        var sql = databaseProduct.contains("mysql")
                ? """
                select column_comment
                from information_schema.columns
                where table_schema = database() and table_name = ? and column_name = ?
                """
                : """
                select remarks
                from information_schema.columns
                where lower(table_name) = ? and lower(column_name) = ?
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizedTableName(databaseProduct));
            statement.setString(2, columnName);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    /**
     * 按数据库方言写入表级注释。
     */
    private void applyTableComment(Connection connection, String databaseProduct) throws SQLException {
        var sql = databaseProduct.contains("mysql")
                ? "alter table " + quoteIdentifier(tableName()) + " comment = '" + escapeSql(TABLE_COMMENT) + "'"
                : "comment on table " + tableName() + " is '" + escapeSql(TABLE_COMMENT) + "'";
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * 按数据库方言写入字段注释。MySQL 修改字段注释时需要带完整字段定义，因此从元数据读取当前定义。
     */
    private void applyColumnComment(
            Connection connection,
            String databaseProduct,
            String columnName,
            String comment
    ) throws SQLException {
        var sql = databaseProduct.contains("mysql")
                ? "alter table " + quoteIdentifier(tableName()) + " modify column "
                + mysqlColumnDefinition(connection, columnName, comment)
                : "comment on column " + tableName() + "." + columnName + " is '" + escapeSql(comment) + "'";
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * 生成 MySQL `modify column` 所需的完整字段定义，保留原字段类型、空值约束、默认值和 extra 属性。
     */
    private String mysqlColumnDefinition(Connection connection, String columnName, String comment) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select column_type, is_nullable, column_default, extra
                from information_schema.columns
                where table_schema = database()
                  and table_name = ?
                  and column_name = ?
                """)) {
            statement.setString(1, tableName());
            statement.setString(2, columnName);
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("未找到字段定义：" + tableName() + "." + columnName);
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

    private Connection connection() throws SQLException {
        var jdbc = properties.getJdbc();
        if (jdbc.getUrl().isBlank()) {
            throw new IllegalStateException("JDBC 快照存储 URL 不能为空");
        }
        return JdbcConnectionFactory.open(jdbc);
    }

    private String tableName() {
        return properties.validatedTableName();
    }

    private String normalizedTableName(String databaseProduct) {
        return databaseProduct.contains("mysql") ? tableName() : tableName().toLowerCase();
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static Map<String, String> orderedMap(String... pairs) {
        var map = new LinkedHashMap<String, String>();
        for (var index = 0; index < pairs.length; index += 2) {
            map.put(pairs[index], pairs[index + 1]);
        }
        return Map.copyOf(map);
    }

    PersistenceModeProperties properties() {
        return properties;
    }

    public record StoredSnapshot(String sliceKey, int version, String payload) {
    }
}
