# MatrixCode 模型缓存策略视图设计

## 背景

第 51 阶段接入供应商真实 prompt cache usage，第 52 阶段持久化缓存来源、缓存作用域和 prefix fingerprint，第 55 阶段完成稳定平台前缀治理。当前仍缺一个可上线使用的策略视图：用户能看到缓存命中率，却看不到本次请求采用了哪种缓存策略，以及角色提示词、动态上下文是否被作为 volatile 后缀处理。

## 目标

- 在模型请求用量记录中保存低敏缓存策略元数据。
- 在桌面端右侧模型网关卡片展示最近请求的缓存策略。
- 新增字段必须进入正式 MySQL Flyway 迁移，并写详细字段注释。
- 不保存完整 prompt、角色提示词全文、用户输入、向量召回正文、工具输出、API Key 或数据库密码。

## 字段设计

在 `UsageRecord` 中新增：

- `cachePolicyId`：缓存策略版本，例如 `stable-platform-prefix-v1`。
- `volatileSuffixStrategy`：动态后缀处理策略，例如 `role-prompt-and-dynamic-context`。

在 `matrixcode_model_requests` 中新增同名蛇形字段：

- `cache_policy_id varchar(80) not null default ''`
- `volatile_suffix_strategy varchar(120) not null default ''`

新增索引：

- `idx_mc_model_requests_cache_policy(project_id, cache_policy_id, created_at)`

## 展示设计

右侧“模型网关”卡片在已有供应商、token、费用指标下补充：

- `缓存策略 stable-platform-prefix-v1`
- `volatile role-prompt-and-dynamic-context`

没有策略字段的历史记录保持不展示，避免旧数据产生噪音。

## 测试策略

- `ModelGatewayServiceTest`：模型请求返回的 `UsageRecord` 带策略字段。
- `MybatisPlusProjectActivityRepositoryTest`：MyBatis-Plus 主路径保存恢复策略字段。
- `JdbcProjectActivityRepositoryTest`：旧兼容 JDBC 仓储保存恢复策略字段。
- `DatabaseMigrationCommentPolicyTest`：新增 DDL 字段注释通过门禁。
- `desktop/src/test/App.test.tsx`：右侧模型网关卡片展示缓存策略和 volatile 策略。

## 回溯对齐

- 对齐 DeepSeek/Reasonix 缓存目标：不仅让稳定前缀命中，还要让用户能看懂策略是否生效。
- 对齐第 52 阶段：缓存 trace 字段继续向可观测性演进。
- 对齐第 55 阶段：`stable-platform-prefix-v1` 成为可展示的策略身份。
- 对齐上线约束：新增 DDL 写字段注释，正式运行继续使用 MySQL，H2 仅用于测试。
