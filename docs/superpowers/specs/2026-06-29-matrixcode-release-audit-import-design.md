# MatrixCode 发布脚本审计导入部署记录设计

## 目标

第 142 阶段把第 139 阶段形成的生产回滚 JSONL 审计接入平台部署记录，让脚本级审计可以进入 `matrixcode_deployment_operations`，并在工作台部署摘要中被查询。

## 范围

- 新增 `DeploymentReleaseAuditImportService`，解析低敏 JSONL 审计行。
- 新增 `DeploymentReleaseAuditImportResult`，返回导入数量、跳过数量和实际写入记录。
- `DeploymentOperationService` 新增外部审计记录写入入口，支持确定性 ID 幂等。
- 工作台 API 新增：
  - `POST /api/projects/{projectId}/deployments/targets/{targetId}/release-audit-imports`
- 桌面端 API client 新增 `importDeploymentReleaseAudit(...)`。

## 非目标

- 不让生产脚本直接调用服务端接口。
- 不新增 Flyway 表结构，继续复用 `matrixcode_deployment_operations`。
- 不导入 `.env`、密钥、token、完整系统日志或脚本 stdout/stderr。
- 不改变发布、安装、重启和回滚脚本的显式确认边界。

## 安全边界

- 导入接口要求当前请求身份是项目成员，且与请求体 `actorId` 一致。
- 只支持 `rollback`、`deploy`、`deployment`、`install` 等明确动作。
- 只支持 `RECORDED`、`SUCCEEDED`、`FAILED` 等部署记录状态。
- 空行、非法 JSON、非法动作或非法状态会被跳过，不阻塞同批有效审计行。
- 导入摘要只包含 source、target、previous、failed 等低敏路径线索，不保存环境变量和凭据。

## 验证

- 红灯：目标测试确认旧项目缺少导入服务和导入接口。
- 目标绿灯：
  - `DeploymentOperationServiceTest,WorkbenchControllerTest` 共 36 条通过。
  - `desktop/src/api/client.test.ts` 共 74 条通过。
- 全量门禁：完成第 142 阶段后执行服务端全量、桌面端全量、桌面构建、生产就绪聚合门禁和敏感扫描。

## 回溯

- 与第 139 阶段一致：先保留脚本侧低敏 JSONL，再由平台受控导入。
- 与上线标准一致：复用已有部署记录正式表，不扩大生产脚本权限。
- 与最初“运维交给项目组：部署记录、运行状态、回滚或事故状态”目标一致：回滚脚本审计可以进入平台记录闭环。
