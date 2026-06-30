# MatrixCode Agent Runtime 写接口鉴权设计

## 背景

第 75 到 77 阶段已经完成用户责任审计权限边界、签名 actor token 和桌面端身份令牌入口。当前剩余风险是 Agent Runtime 写接口仍主要信任 query 参数中的 `actorUserId` 或 `workerId`，如果后端开启强制签名，这些写接口还没有统一通过 `RequestActorResolver` 校验请求身份。

## 推荐方案

采用低风险兼容方案：

- 保留现有 query 参数，避免破坏服务层审计语义和桌面端调用路径。
- 控制器在执行写操作前解析请求身份，并要求解析出的 actor 与 query 参数一致。
- 桌面端对重试、认领、认领下一条等当前操作者写操作追加 `X-MatrixCode-User-Id` 和可选 Bearer token。
- Worker 类接口同样校验 `workerId` 与请求身份一致，为后续真实 Worker 使用签名身份做准备。

## 覆盖接口

- `POST /api/projects/{projectId}/agent-runs/{runId}/retry`
- `POST /api/projects/{projectId}/agent-runs/{runId}/claim`
- `POST /api/projects/{projectId}/agent-runs/claim-next`
- `POST /api/projects/{projectId}/agent-runs/{runId}/renew-lease`
- `POST /api/projects/{projectId}/agent-runs/worker-tick`
- `POST /api/projects/{projectId}/agent-runs/{runId}/worker-execution-plan`
- `POST /api/projects/{projectId}/agent-runs/{runId}/worker-model-request`

## 安全边界

- 不改变服务层业务规则，不新增 DDL。
- 不把 prompt、模型响应、向量正文、工具输出、命令输出或密钥放入身份令牌或错误响应。
- 默认兼容模式仍允许身份头；生产强制签名由第 76 阶段配置控制。

## 验证标准

- 控制器测试覆盖缺少身份返回 401、身份与 query 参数不一致返回 403。
- 既有写操作测试补齐身份头后继续通过。
- 桌面端 API client 测试覆盖 Agent Runtime 写操作透传 actor headers。
- 服务端目标测试、桌面端目标测试、全量构建和静态扫描通过。
