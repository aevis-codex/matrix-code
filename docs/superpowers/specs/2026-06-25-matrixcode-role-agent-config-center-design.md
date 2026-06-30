# MatrixCode 第十九阶段角色智能体配置中心设计规格

第十八阶段已经创建 `matrixcode_role_agent_configs` 领域表，但当前服务默认仍以文件快照运行，真实 MySQL 地址也尚未提供。第十九阶段先做“角色智能体配置中心”的可用纵切：后端提供配置领域模型、服务和 API，桌面端提供查看与编辑入口，默认文件模式和 JDBC 快照模式都能验证。

## 目标

- 为产品、开发、测试、运维角色提供独立智能体配置。
- 配置项覆盖系统提示词、用户提示词模板、模型供应商、模型名称、工具契约版本、颜色、字体、字号、排序和启停状态。
- 后端提供列表和更新 API。
- 桌面端在工作台中展示每个角色智能体配置，并允许编辑核心配置。
- 默认文件模式下配置进入 `.matrixcode/workbench-state.json`；JDBC 快照模式下配置进入 `workbench-state` 切片。
- 与第十八阶段 MySQL 表结构保持字段一致，后续可平滑切到 `matrixcode_role_agent_configs` 正式表。

## 非目标

- 不在本阶段接入真实 MySQL 表读写；真实数据库地址提供后再切仓储。
- 不实现登录、用户权限或多租户隔离。
- 不实现完整编码智能体工具协议。
- 不接入 Milvus、Redis、RocketMQ。
- 不在本阶段让模型网关实际套用 `systemPrompt` 和 `userPromptTemplate`；第十九阶段先完成配置中心，下一阶段再接入模型请求运行链路。

## 领域模型

新增 `RoleAgentConfig`：

- `projectId`
- `role`
- `displayName`
- `agentKind`
- `providerId`
- `model`
- `toolContractVersion`
- `systemPrompt`
- `userPromptTemplate`
- `themeColor`
- `fontFamily`
- `fontSize`
- `sortOrder`
- `enabled`
- `updatedAt`

默认配置：

- 产品：需求澄清、PRD、验收标准。
- 开发：任务拆解、代码修改、测试验证、回溯记录。
- 测试：缺陷分析、复现步骤、回归策略。
- 运维：部署检查、健康监控、回滚记录。

## 存储边界

新增 `WorkbenchStateSnapshot.roleAgentConfigs`，并在 `WorkbenchStateStore` 增加 `saveRoleAgentConfigs(...)`。

原因：

- 当前默认文件模式无需数据库即可运行。
- 已有 JDBC 快照模式会自动保存 `workbench-state` 切片。
- 后续切到正式 MySQL 表时，API 和桌面端契约不需要变化。

## 服务与 API

新增 `RoleAgentConfigService`：

- `configs(projectId)`：返回四个角色配置，缺失时补默认配置。
- `require(projectId, role)`：返回单角色配置。
- `update(projectId, role, command)`：校验并保存单角色配置。

新增 `RoleAgentConfigController`：

- `GET /api/projects/{projectId}/role-agent-configs`
- `PUT /api/projects/{projectId}/role-agent-configs/{role}`

## 桌面端体验

在右侧检查器中新增“角色智能体配置”区域：

- 使用当前角色列表显示产品、开发、测试、运维配置。
- 每个角色展示主题色、模型、工具契约、启停状态和提示词摘要。
- 选择一个角色后可以编辑系统提示词、用户提示词模板、模型、颜色、字体和字号。
- 保存后刷新工作台配置，不影响当前其他工作流操作。

## 验收标准

- 服务端定向测试先红后绿。
- 默认文件模式下配置更新后重建服务可恢复。
- JDBC 快照模式下配置更新后 Spring 上下文重启可恢复。
- API 测试覆盖列表、更新、非法角色和空提示词校验。
- 桌面端测试覆盖配置展示、编辑提交和失败提示。
- 服务端全量、桌面测试、桌面构建和 Tauri build help 通过。
- Obsidian `MatrixCode` 图谱新增第十九阶段成果页，并更新首页、阶段索引、模块地图、技术栈、验证风险和持久化模块。

## 后续阶段入口

第二十阶段建议把角色智能体配置接入模型网关运行链路：`systemPrompt` 进入 `PromptContract`，`userPromptTemplate` 包装用户指令，模型字段同步到现有角色模型绑定，并为开发编码智能体引入工具协议与执行计划记录。
