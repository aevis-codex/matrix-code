package com.matrixcode.persistence.application;

import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.roleagent.application.RoleAgentConfigRepository;
import com.matrixcode.roleagent.domain.RoleAgentConfig;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class JdbcRoleAgentConfigRepository implements RoleAgentConfigRepository {

    private final PersistenceModeProperties properties;
    private final DatabaseMigrationService migrationService;
    private boolean migrated;

    public JdbcRoleAgentConfigRepository(
            PersistenceModeProperties properties,
            DatabaseMigrationService migrationService
    ) {
        this.properties = properties;
        this.migrationService = migrationService;
    }

    public JdbcRoleAgentConfigRepository(PersistenceModeProperties properties) {
        this.properties = properties;
        this.migrationService = null;
    }

    @Override
    public List<RoleAgentConfig> load() {
        ensureSchema();
        var configs = new ArrayList<RoleAgentConfig>();
        try (var connection = connection();
             var statement = connection.prepareStatement("""
                     select project_id, role_key, display_name, agent_kind, model_provider, model_name,
                            tool_contract_version, cache_policy_id, volatile_suffix_strategy, cache_scope_strategy,
                            system_prompt, user_prompt_template, theme_color,
                            font_family, font_size, sort_order, enabled, updated_at
                     from matrixcode_role_agent_configs
                     order by project_id, sort_order, role_key
                     """);
             var resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                configs.add(readConfig(resultSet));
            }
            return configs;
        } catch (SQLException exception) {
            throw new IllegalStateException("角色智能体配置表读取失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public void save(List<RoleAgentConfig> configs) {
        if (configs == null || configs.isEmpty()) {
            return;
        }
        ensureSchema();
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                for (var config : configs) {
                    ensureProject(connection, config.projectId());
                    if (updateConfig(connection, config) == 0) {
                        insertConfig(connection, config);
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
            throw new IllegalStateException("角色智能体配置表写入失败：" + exception.getMessage(), exception);
        }
    }

    private RoleAgentConfig readConfig(ResultSet resultSet) throws SQLException {
        return new RoleAgentConfig(
                resultSet.getString("project_id"),
                ModelRole.valueOf(resultSet.getString("role_key")),
                resultSet.getString("display_name"),
                resultSet.getString("agent_kind"),
                resultSet.getString("model_provider"),
                resultSet.getString("model_name"),
                resultSet.getString("tool_contract_version"),
                resultSet.getString("system_prompt"),
                resultSet.getString("user_prompt_template"),
                resultSet.getString("theme_color"),
                resultSet.getString("font_family"),
                resultSet.getInt("font_size"),
                resultSet.getInt("sort_order"),
                resultSet.getBoolean("enabled"),
                resultSet.getString("cache_policy_id"),
                resultSet.getString("volatile_suffix_strategy"),
                resultSet.getString("cache_scope_strategy"),
                instant(resultSet.getTimestamp("updated_at"))
        );
    }

    private int updateConfig(Connection connection, RoleAgentConfig config) throws SQLException {
        try (var statement = connection.prepareStatement("""
                update matrixcode_role_agent_configs
                set display_name = ?, agent_kind = ?, model_provider = ?, model_name = ?,
                    tool_contract_version = ?, cache_policy_id = ?, volatile_suffix_strategy = ?,
                    cache_scope_strategy = ?, system_prompt = ?, user_prompt_template = ?, theme_color = ?, font_family = ?,
                    font_size = ?, sort_order = ?, enabled = ?,
                    updated_at = ?
                where project_id = ? and role_key = ?
                """)) {
            bindMutableConfigFields(statement, config);
            statement.setTimestamp(16, timestamp(config.updatedAt()));
            statement.setString(17, config.projectId());
            statement.setString(18, config.role().name());
            return statement.executeUpdate();
        }
    }

    private void insertConfig(Connection connection, RoleAgentConfig config) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into matrixcode_role_agent_configs
                    (id, project_id, role_key, display_name, agent_kind, model_provider, model_name,
                     tool_contract_version, cache_policy_id, volatile_suffix_strategy, cache_scope_strategy, system_prompt,
                     user_prompt_template, theme_color, font_family, font_size, sort_order, enabled,
                     created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, configId(config.projectId(), config.role()));
            statement.setString(2, config.projectId());
            statement.setString(3, config.role().name());
            bindMutableConfigFields(statement, config, 4);
            statement.setTimestamp(19, timestamp(config.updatedAt()));
            statement.setTimestamp(20, timestamp(config.updatedAt()));
            statement.executeUpdate();
        }
    }

    private void bindMutableConfigFields(java.sql.PreparedStatement statement, RoleAgentConfig config)
            throws SQLException {
        bindMutableConfigFields(statement, config, 1);
    }

    private void bindMutableConfigFields(java.sql.PreparedStatement statement, RoleAgentConfig config, int start)
            throws SQLException {
        statement.setString(start, config.displayName());
        statement.setString(start + 1, config.agentKind());
        statement.setString(start + 2, config.providerId());
        statement.setString(start + 3, config.model());
        statement.setString(start + 4, config.toolContractVersion());
        statement.setString(start + 5, config.cachePolicyId());
        statement.setString(start + 6, config.volatileSuffixStrategy());
        statement.setString(start + 7, config.cacheScopeStrategy());
        statement.setString(start + 8, config.systemPrompt());
        statement.setString(start + 9, config.userPromptTemplate());
        statement.setString(start + 10, config.themeColor());
        statement.setString(start + 11, config.fontFamily());
        statement.setInt(start + 12, config.fontSize());
        statement.setInt(start + 13, config.sortOrder());
        statement.setBoolean(start + 14, config.enabled());
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
            statement.setString(6, "角色智能体配置");
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

    private String configId(String projectId, ModelRole role) {
        return projectId.trim() + "::" + role.name();
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant == null ? Instant.EPOCH : instant);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? Instant.EPOCH : timestamp.toInstant();
    }
}
