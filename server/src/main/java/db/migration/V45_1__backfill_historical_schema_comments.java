package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 为第 42 阶段之前已经执行过的历史正式表补齐表注释和字段注释。
 *
 * <p>不能直接修改早期 SQL 迁移，否则真实库的 Flyway checksum 会失效。本迁移通过新的
 * v45.1 版本追加注释：MySQL 使用 `alter table` / `modify column`，H2 测试库使用
 * `comment on` 语法，保证正式环境和测试环境都能验证同一批注释要求。</p>
 */
public class V45_1__backfill_historical_schema_comments extends BaseJavaMigration {

    private static final Map<String, String> TABLE_COMMENTS = orderedMap(
            "matrixcode_users", "保存 MatrixCode 用户账号基础信息，用于项目成员、审计归属和协作身份识别。",
            "matrixcode_projects", "保存 MatrixCode 项目基础信息、负责人和当前业务阶段。",
            "matrixcode_project_members", "保存项目成员、成员角色和成员状态，用于多人协作和权限判断。",
            "matrixcode_role_agent_configs", "保存每个项目角色智能体的模型、提示词、工具契约和界面偏好配置。",
            "matrixcode_collaboration_sessions", "保存多人协作会话的生命周期、发起人和状态。",
            "matrixcode_collaboration_participants", "保存协作会话参与者、角色、在线状态和光标标识。",
            "matrixcode_documents", "保存项目文档、版本、冻结状态和角色交付内容。",
            "matrixcode_bugs", "保存测试缺陷、严重级别、状态流转、责任角色和复现信息。",
            "matrixcode_deployment_targets", "保存部署目标、环境地址、健康检查地址和发布回滚说明。",
            "matrixcode_runtime_notifications", "保存运行态提醒、收件用户、来源对象和已读状态。",
            "matrixcode_local_execution_tasks", "保存本地执行任务、审批决策、执行结果和取消信息。",
            "matrixcode_audit_records", "保存本地执行、审批和用户操作审计记录。",
            "matrixcode_project_events", "保存项目级事件流，用于工作台动态和实时同步。",
            "matrixcode_local_workspaces", "保存已授权本地工作区及其访问状态。",
            "matrixcode_local_task_logs", "保存本地执行任务日志片段。",
            "matrixcode_deployment_operations", "保存部署或回滚操作记录。",
            "matrixcode_deployment_health_checks", "保存部署目标健康检查结果。",
            "matrixcode_compose_environments", "保存 Docker Compose 演示环境配置。",
            "matrixcode_compose_operations", "保存 Docker Compose 校验、启动、停止和日志采集结果。",
            "matrixcode_model_requests", "保存模型网关请求、供应商、模型、用量和上下文摘要。",
            "matrixcode_workflow_items", "保存工作流待办项和当前状态。",
            "matrixcode_workflow_events", "保存工作流状态变更事件。",
            "matrixcode_acceptance_states", "保存项目验收投影状态。"
    );

    private static final Map<String, Map<String, String>> COLUMN_COMMENTS = Map.ofEntries(
            table("matrixcode_users",
                    "id", "用户 ID，作为用户在系统内的稳定唯一标识。",
                    "username", "登录名或系统内唯一用户名。",
                    "display_name", "用户展示名称，用于工作台、审计和成员列表展示。",
                    "email", "用户邮箱，预留给邀请、通知和外部身份集成。",
                    "status", "用户状态，例如 ACTIVE、DISABLED。",
                    "created_at", "用户记录创建时间。",
                    "updated_at", "用户记录最后更新时间。"),
            table("matrixcode_projects",
                    "id", "项目 ID，作为项目级数据隔离和关联主键。",
                    "name", "项目名称，展示在桌面端和项目列表。",
                    "description", "项目描述，记录项目目标、范围或补充说明。",
                    "owner_user_id", "项目负责人用户 ID，关联 matrixcode_users.id。",
                    "status", "项目状态，例如 ACTIVE、ARCHIVED。",
                    "current_stage", "当前业务阶段，用于工作台阶段条和协作视图。",
                    "created_at", "项目创建时间。",
                    "updated_at", "项目最后更新时间。"),
            table("matrixcode_project_members",
                    "id", "项目成员关系 ID。",
                    "project_id", "所属项目 ID，关联 matrixcode_projects.id。",
                    "user_id", "成员用户 ID，关联 matrixcode_users.id。",
                    "role_key", "成员角色键，例如 OWNER、PRODUCT、DEVELOPER、TESTER、OPERATIONS。",
                    "status", "成员状态，例如 ACTIVE、REMOVED。",
                    "joined_at", "成员加入项目时间。",
                    "created_at", "成员关系创建时间。",
                    "updated_at", "成员关系最后更新时间。"),
            table("matrixcode_role_agent_configs",
                    "id", "角色智能体配置 ID。",
                    "project_id", "所属项目 ID，关联 matrixcode_projects.id。",
                    "role_key", "角色键，例如 PRODUCT、DEVELOPER、TESTER、OPERATIONS。",
                    "display_name", "角色智能体展示名称。",
                    "agent_kind", "智能体类型，例如 product、coding、testing、operations。",
                    "model_provider", "默认模型供应商 ID，例如 qwen、deepseek、kimi、doubao。",
                    "model_name", "默认模型名称，记录角色默认使用模型。",
                    "tool_contract_version", "工具契约版本，用于约束智能体可用工具协议。",
                    "temperature", "模型采样温度，控制输出随机性。",
                    "system_prompt", "角色智能体系统提示词。",
                    "user_prompt_template", "角色智能体用户提示词模板。",
                    "theme_color", "角色智能体主题色，用于桌面端展示。",
                    "font_family", "角色智能体展示字体。",
                    "font_size", "角色智能体展示字号。",
                    "sort_order", "角色智能体排序值。",
                    "enabled", "是否启用该角色智能体配置。",
                    "created_at", "配置创建时间。",
                    "updated_at", "配置最后更新时间。"),
            table("matrixcode_collaboration_sessions",
                    "id", "协作会话 ID。",
                    "project_id", "所属项目 ID，关联 matrixcode_projects.id。",
                    "title", "协作会话标题。",
                    "status", "协作会话状态，例如 ACTIVE、ENDED。",
                    "started_by_user_id", "发起协作会话的用户 ID，关联 matrixcode_users.id。",
                    "started_at", "协作会话开始时间。",
                    "ended_at", "协作会话结束时间，未结束时为空。",
                    "created_at", "会话记录创建时间。",
                    "updated_at", "会话记录最后更新时间。"),
            table("matrixcode_collaboration_participants",
                    "id", "协作参与者记录 ID。",
                    "session_id", "所属协作会话 ID，关联 matrixcode_collaboration_sessions.id。",
                    "user_id", "参与用户 ID，关联 matrixcode_users.id；智能体参与者可为空。",
                    "role_key", "参与者角色键。",
                    "agent_config_id", "参与智能体配置 ID，关联 matrixcode_role_agent_configs.id。",
                    "presence_status", "在线状态，例如 ONLINE、IDLE、OFFLINE。",
                    "cursor_label", "协作光标或当前位置展示标签。",
                    "joined_at", "参与者加入会话时间。",
                    "last_seen_at", "参与者最后活跃时间。",
                    "created_at", "参与者记录创建时间。",
                    "updated_at", "参与者记录最后更新时间。"),
            table("matrixcode_documents",
                    "id", "文档 ID。",
                    "project_id", "所属项目 ID，关联 matrixcode_projects.id。",
                    "document_type", "文档类型，例如 PRD、QA_REPORT、CODING_AGENT_HANDOFF。",
                    "title", "文档标题。",
                    "status", "文档状态，例如 DRAFT、REVIEW_PENDING、FROZEN。",
                    "version", "文档版本号。",
                    "frozen", "文档是否已冻结。",
                    "content", "文档正文内容。",
                    "created_by_role", "创建文档的角色名称。",
                    "updated_by_role", "最后更新文档的角色名称。",
                    "created_at", "文档创建时间。",
                    "updated_at", "文档最后更新时间。",
                    "parent_version_id", "父版本文档 ID，用于文档版本链追踪。",
                    "frozen_by", "冻结文档的操作者或角色。",
                    "frozen_at", "文档冻结时间。"),
            table("matrixcode_bugs",
                    "id", "缺陷 ID。",
                    "project_id", "所属项目 ID，关联 matrixcode_projects.id。",
                    "title", "缺陷标题。",
                    "description", "缺陷描述或补充背景。",
                    "severity", "严重级别，例如 LOW、MEDIUM、HIGH、BLOCKER。",
                    "status", "缺陷状态，例如 NEW、CONFIRMED、FIXING、CLOSED。",
                    "created_by_role", "创建缺陷的角色。",
                    "current_owner_role", "当前负责处理缺陷的角色。",
                    "related_document_id", "关联文档 ID，关联 matrixcode_documents.id。",
                    "created_at", "缺陷创建时间。",
                    "updated_at", "缺陷最后更新时间。",
                    "reproduction_steps", "复现步骤。",
                    "expected_result", "期望结果。",
                    "actual_result", "实际结果。",
                    "last_note", "最近一次缺陷流转备注。"),
            table("matrixcode_deployment_targets",
                    "id", "部署目标 ID。",
                    "project_id", "所属项目 ID，关联 matrixcode_projects.id。",
                    "name", "部署目标名称。",
                    "environment_key", "环境键，例如 dev、test、staging、prod。",
                    "provider", "部署供应商或环境类型。",
                    "status", "部署目标状态。",
                    "endpoint_url", "部署目标访问地址。",
                    "created_at", "部署目标创建时间。",
                    "updated_at", "部署目标最后更新时间。",
                    "ssh_address", "部署目标 SSH 地址。",
                    "deploy_note", "部署操作说明。",
                    "health_check_url", "健康检查 URL。",
                    "rollback_note", "回滚说明。",
                    "remote_executed", "是否已触发远程部署动作。"),
            table("matrixcode_runtime_notifications",
                    "id", "运行态提醒 ID。",
                    "project_id", "所属项目 ID，关联 matrixcode_projects.id。",
                    "user_id", "提醒收件用户 ID，关联 matrixcode_users.id。",
                    "level_key", "提醒级别，例如 ACTION、SUCCESS、WARNING、ERROR。",
                    "source_type", "提醒来源类型，例如 APPROVAL、LOCAL_TASK、COMPOSE_OPERATION。",
                    "source_id", "提醒来源对象 ID。",
                    "title", "提醒标题。",
                    "message", "提醒正文。",
                    "read_at", "提醒已读时间，未读时为空。",
                    "created_at", "提醒创建时间。",
                    "updated_at", "提醒最后更新时间。"),
            table("matrixcode_local_execution_tasks",
                    "id", "本地执行任务 ID。",
                    "project_id", "所属项目 ID，关联 matrixcode_projects.id。",
                    "workspace_id", "授权工作区 ID。",
                    "requested_by_role", "发起本地执行的角色。",
                    "approval_record_id", "关联审批记录 ID，预留字段。",
                    "command_text", "待执行或已执行的命令文本。",
                    "status", "执行任务状态。",
                    "exit_code", "命令退出码，未结束时为空。",
                    "started_at", "任务开始执行时间。",
                    "finished_at", "任务结束时间。",
                    "created_at", "任务创建时间。",
                    "updated_at", "任务最后更新时间。",
                    "tool_type", "本地工具类型，例如 SHELL、FILE、GIT。",
                    "approval_decision", "审批决策，例如 ASK、ALLOW、DENY。",
                    "stdout_summary", "标准输出摘要。",
                    "stderr_summary", "标准错误摘要。",
                    "duration_millis", "任务耗时毫秒数。",
                    "approver_id", "审批人 ID。",
                    "approval_note", "审批备注。",
                    "decided_at", "审批决策时间。",
                    "safety_rejection_reason", "安全策略拒绝原因。",
                    "canceled_by", "取消任务的操作者 ID。",
                    "cancel_note", "取消任务说明。",
                    "canceled_at", "任务取消时间。",
                    "sort_order", "任务排序值，用于恢复最近任务顺序。"),
            table("matrixcode_audit_records",
                    "id", "审计记录 ID。",
                    "project_id", "所属项目 ID，关联 matrixcode_projects.id。",
                    "actor_user_id", "操作者用户 ID，关联 matrixcode_users.id。",
                    "actor_role", "操作者角色。",
                    "action_key", "审计动作键。",
                    "target_type", "审计目标类型。",
                    "target_id", "审计目标 ID。",
                    "decision", "审批或安全决策。",
                    "summary", "审计摘要。",
                    "created_at", "审计记录创建时间。",
                    "updated_at", "审计记录最后更新时间。",
                    "task_id", "关联本地执行任务 ID。",
                    "tool_type", "审计涉及的工具类型。",
                    "workspace_path", "审计涉及的工作区路径。",
                    "occurred_at", "审计动作实际发生时间。",
                    "sort_order", "审计排序值。"),
            table("matrixcode_project_events",
                    "id", "项目事件 ID。",
                    "project_id", "所属项目 ID，关联 matrixcode_projects.id。",
                    "event_type", "项目事件类型。",
                    "source_role", "事件来源角色。",
                    "source_id", "事件来源对象 ID。",
                    "title", "事件标题。",
                    "payload", "事件结构化内容或展示摘要。",
                    "created_at", "事件创建时间。",
                    "updated_at", "事件最后更新时间。"),
            table("matrixcode_local_workspaces",
                    "id", "授权工作区 ID。",
                    "project_id", "所属项目 ID，关联 matrixcode_projects.id。",
                    "name", "工作区名称。",
                    "root_path", "工作区根路径。",
                    "status", "工作区授权状态，例如 AUTHORIZED、REVOKED。",
                    "created_at", "工作区授权创建时间。",
                    "last_accessed_at", "工作区最后访问时间。",
                    "updated_at", "工作区记录最后更新时间。"),
            table("matrixcode_local_task_logs",
                    "id", "任务日志 ID。",
                    "project_id", "所属项目 ID，关联 matrixcode_projects.id。",
                    "task_id", "所属本地执行任务 ID，关联 matrixcode_local_execution_tasks.id。",
                    "stream", "日志流类型，例如 STDOUT、STDERR、SYSTEM。",
                    "content", "日志内容片段。",
                    "sort_order", "日志排序值。",
                    "created_at", "日志创建时间。",
                    "updated_at", "日志最后更新时间。"),
            table("matrixcode_deployment_operations",
                    "id", "部署操作 ID。",
                    "project_id", "所属项目 ID，关联 matrixcode_projects.id。",
                    "target_id", "部署目标 ID。",
                    "actor_id", "执行或记录部署操作的用户 ID。",
                    "operation_type", "操作类型，例如 DEPLOYMENT、ROLLBACK。",
                    "status", "操作状态，例如 RECORDED、SUCCEEDED、FAILED。",
                    "note", "操作备注。",
                    "created_at", "操作创建时间。"),
            table("matrixcode_deployment_health_checks",
                    "id", "健康检查 ID。",
                    "project_id", "所属项目 ID，关联 matrixcode_projects.id。",
                    "target_id", "部署目标 ID。",
                    "actor_id", "触发健康检查的用户 ID。",
                    "status", "健康状态，例如 HEALTHY、UNHEALTHY、UNREACHABLE。",
                    "http_status", "健康检查 HTTP 状态码。",
                    "duration_millis", "健康检查耗时毫秒数。",
                    "summary", "健康检查摘要。",
                    "checked_at", "健康检查发生时间。"),
            table("matrixcode_compose_environments",
                    "id", "Compose 环境 ID。",
                    "project_id", "所属项目 ID，关联 matrixcode_projects.id。",
                    "target_id", "关联部署目标 ID。",
                    "workspace_id", "授权工作区 ID。",
                    "compose_file_path", "Compose 文件相对路径。",
                    "project_name", "Compose 项目名称。",
                    "service_name", "主要服务名称。",
                    "status", "Compose 环境状态。",
                    "created_at", "Compose 环境创建时间。",
                    "updated_at", "Compose 环境最后更新时间。"),
            table("matrixcode_compose_operations",
                    "id", "Compose 操作 ID。",
                    "project_id", "所属项目 ID，关联 matrixcode_projects.id。",
                    "environment_id", "Compose 环境 ID。",
                    "actor_id", "触发 Compose 操作的用户 ID。",
                    "operation_type", "操作类型，例如 VALIDATE、START、STOP、LOGS。",
                    "status", "操作状态，例如 SUCCEEDED、FAILED。",
                    "summary", "操作摘要。",
                    "log_excerpt", "操作日志摘录。",
                    "created_at", "操作创建时间。"),
            table("matrixcode_model_requests",
                    "id", "模型请求 ID。",
                    "project_id", "所属项目 ID，关联 matrixcode_projects.id。",
                    "role_key", "发起模型请求的角色键。",
                    "provider_id", "模型供应商 ID。",
                    "model_name", "模型名称。",
                    "answer_summary", "模型回答摘要。",
                    "usage_role_session_id", "用量所属角色会话 ID。",
                    "usage_model", "用量记录中的模型名称。",
                    "cache_hit_tokens", "缓存命中 token 数。",
                    "cache_miss_input_tokens", "未命中输入 token 数。",
                    "output_tokens", "输出 token 数。",
                    "cache_hit_rate", "缓存命中率。",
                    "estimated_cost", "估算费用。",
                    "currency", "费用币种。",
                    "context_types", "参与请求的上下文类型列表文本。",
                    "created_at", "模型请求创建时间。",
                    "actor_user_id", "发起模型请求的用户 ID，关联 matrixcode_users.id。"),
            table("matrixcode_workflow_items",
                    "id", "工作流项 ID。",
                    "project_id", "所属项目 ID，关联 matrixcode_projects.id。",
                    "title", "工作流项标题。",
                    "state", "当前工作流状态。",
                    "created_at", "工作流项创建时间。",
                    "updated_at", "工作流项最后更新时间。"),
            table("matrixcode_workflow_events",
                    "id", "工作流事件 ID。",
                    "project_id", "所属项目 ID，关联 matrixcode_projects.id。",
                    "item_id", "所属工作流项 ID，关联 matrixcode_workflow_items.id。",
                    "event_type", "工作流事件类型。",
                    "from_state", "流转前状态。",
                    "to_state", "流转后状态。",
                    "actor_id", "触发状态流转的操作者 ID。",
                    "occurred_at", "工作流事件发生时间。"),
            table("matrixcode_acceptance_states",
                    "project_id", "所属项目 ID，关联 matrixcode_projects.id。",
                    "document_id", "验收关联文档 ID。",
                    "accepted", "是否验收通过。",
                    "return_to_role", "验收不通过时退回角色。",
                    "updated_at", "验收状态最后更新时间。")
    );

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        var databaseProduct = connection.getMetaData().getDatabaseProductName().toLowerCase();
        for (var tableComment : TABLE_COMMENTS.entrySet()) {
            applyTableComment(connection, databaseProduct, tableComment.getKey(), tableComment.getValue());
        }
        for (var tableColumns : COLUMN_COMMENTS.entrySet()) {
            for (var columnComment : tableColumns.getValue().entrySet()) {
                applyColumnComment(connection, databaseProduct, tableColumns.getKey(), columnComment.getKey(), columnComment.getValue());
            }
        }
    }

    /**
     * 按数据库方言写入表级注释。
     */
    private void applyTableComment(Connection connection, String databaseProduct, String tableName, String comment) throws SQLException {
        var sql = databaseProduct.contains("mysql")
                ? "alter table " + quoteIdentifier(tableName) + " comment = '" + escapeSql(comment) + "'"
                : "comment on table " + tableName + " is '" + escapeSql(comment) + "'";
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * 按数据库方言写入字段注释。MySQL 修改字段注释时必须带完整字段定义，因此从 information_schema
     * 读取当前定义后再追加 comment，避免改变字段类型、空值约束和默认值。
     */
    private void applyColumnComment(
            Connection connection,
            String databaseProduct,
            String tableName,
            String columnName,
            String comment
    ) throws SQLException {
        var sql = databaseProduct.contains("mysql")
                ? "alter table " + quoteIdentifier(tableName) + " modify column " + mysqlColumnDefinition(connection, tableName, columnName, comment)
                : "comment on column " + tableName + "." + columnName + " is '" + escapeSql(comment) + "'";
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String mysqlColumnDefinition(Connection connection, String tableName, String columnName, String comment) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select column_type, is_nullable, column_default, extra
                from information_schema.columns
                where table_schema = database()
                  and table_name = ?
                  and column_name = ?
                """)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("未找到字段定义：" + tableName + "." + columnName);
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

    private static Map.Entry<String, Map<String, String>> table(String tableName, String... pairs) {
        return Map.entry(tableName, orderedMap(pairs));
    }
}
