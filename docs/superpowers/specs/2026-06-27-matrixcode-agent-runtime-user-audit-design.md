# MatrixCode Agent Runtime 用户责任审计设计

## 背景

第 72 阶段已经允许当前认领 Worker 在有效租约内触发一次模型请求，并把请求强关联到 Agent Run。上线前还需要一个用户级责任链视图：能回答某个用户在项目中负责哪些 Agent 运行、哪些运行已经触发模型请求、当前责任来源是什么。

## 推荐方案

新增只读应用服务 `AgentRuntimeUserAuditService`：

- 从 `AgentRuntimeService.recentRuns(...)` 读取项目最近 Agent 运行。
- 从 `ModelGatewayService.recentRequests(...)` 读取已经脱敏的模型请求。
- 可选读取 `ProjectIdentityService.members(...)`，按角色匹配审批责任人。
- 责任人规则：当前租约认领人优先；否则按运行角色匹配项目成员；仍找不到则回退到运行操作者。
- 只返回低敏摘要：运行 ID、状态、角色、Agent 类型、操作者、认领人、责任人、责任来源、事件计数、工具 trace 数、模型请求数、最近模型请求 ID 和短摘要。

## API

新增：

```http
GET /api/projects/{projectId}/agent-runs/user-audit?userId=...
```

返回 `AgentRuntimeUserAuditReport`：

- `projectId`
- `userId`
- `totalRuns`
- `activeResponsibilities`
- `modelRequestCount`
- `entries`

## 边界

- 不新增 Flyway DDL。
- 不执行命令、不读写文件、不应用 Patch。
- 不返回完整 prompt、模型响应全文、向量正文、命令输出、文件内容、API Key 或数据库密码。
- 不实现完整登录认证；`userId` 仍是当前桌面协作上下文中的用户 ID。

## 验收

- 用户作为认领人时，审计报告包含该运行，责任来源为 `CLAIMED_WORKER`。
- 用户作为运行操作者且模型请求操作者一致时，报告包含模型请求计数和最近模型请求 ID。
- 用户不相关时，报告为空。
- HTTP 入口返回同一报告结构。
- 服务端全量、桌面端全量、桌面构建、真实运行检查、真实集成、静态检查和敏感信息扫描通过。
