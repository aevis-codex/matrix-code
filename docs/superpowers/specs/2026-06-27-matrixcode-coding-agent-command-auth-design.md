# MatrixCode 编码智能体写接口鉴权设计

## 背景

第 76 到 79 阶段已经形成 actor token、Agent Runtime 写接口鉴权和本地执行命令接口鉴权。编码智能体接口仍存在同类缺口：任务规划、执行准备、受控 Patch 和交付回溯请求体都有 `actorId`，但控制器尚未校验请求身份是否与 `actorId` 一致。

这些接口会创建 Agent Run、提交测试命令、写入本地文件或写入交付文档，属于上线前必须收紧的高风险写链路。

## 推荐方案

收紧已有 `actorId` 的编码智能体写接口：

- `POST /api/projects/{projectId}/roles/{role}/coding-agent/tasks`
- `POST /api/projects/{projectId}/roles/{role}/coding-agent/execution-plans`
- `POST /api/projects/{projectId}/roles/{role}/coding-agent/patches`
- `POST /api/projects/{projectId}/roles/{role}/coding-agent/handoffs`

控制器通过 `RequestActorResolver` 解析请求身份，并要求请求身份与请求体 `actorId` 一致。缺少请求身份返回 401，身份不一致返回 403，缺少 `actorId` 返回 400。

桌面端对已暴露的三个入口同步透传 actor headers：

- `prepareCodingAgentExecution(...)`
- `applyCodingAgentPatch(...)`
- `recordCodingAgentHandoff(...)`

## 本阶段不做

- 不新增完整登录 UI。
- 不新增 DDL。
- 不改变编码智能体任务步骤、审批策略、Patch 内容匹配策略或交付回溯内容。
- 不让编码智能体自动执行命令、自动批准命令或绕过 Patch 审批。
- 不处理没有前端入口的额外 UI 流程，只覆盖 API client 中已存在的写调用。

## 验证标准

- 缺少身份头创建编码任务返回 401。
- 请求身份与任务规划 `actorId` 不一致返回 403。
- 请求身份与执行准备 `actorId` 不一致返回 403。
- 请求身份与受控 Patch `actorId` 不一致返回 403。
- 请求身份与交付回溯 `actorId` 不一致返回 403。
- 桌面端执行准备、受控 Patch、交付回溯请求携带 `X-MatrixCode-User-Id` 和可选 Bearer token。
- 完整验证通过桌面端测试、桌面构建、服务端测试、真实运行检查、静态检查和敏感信息扫描。
