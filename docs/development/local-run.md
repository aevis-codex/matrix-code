# 本地运行指南

## 环境要求

- Java 21
- Maven 3.9+
- Node.js 22+
- Docker Desktop

## 本机 Maven 约定

当前本机 Maven 可执行文件路径：

```bash
/Users/Masons/Ai/Maven/bin/mvn
```

当前本机 Maven 仓库路径：

```bash
/Users/Masons/Ai/Maven_Ai_Store
```

服务端命令统一显式指定本地仓库，避免误用临时 Maven 或临时依赖缓存。

## 启动依赖

```bash
docker compose up -d
```

## 运行服务端测试

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

## 启动服务端

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -pl server spring-boot:run
```

普通开发服务默认地址为 `http://localhost:8080`。

真实 MySQL、Milvus、Redis、RocketMQ 和外部模型联调建议使用专用脚本启动：

```bash
./scripts/run-real-local.sh
```

该脚本会先执行真实运行配置检查，并默认把服务端口设为 `18080`，避免和普通开发服务的 `8080` 冲突。桌面端默认连接 `http://localhost:8080`；使用真实联调脚本时需要显式指定：

```bash
VITE_MATRIXCODE_SERVER_URL=http://localhost:18080 npm --prefix desktop run dev
```

可通过 `SERVER_PORT=18081 ./scripts/run-real-local.sh` 覆盖真实联调端口。

## 当前正式运行约定

- 正式业务数据库使用 MySQL，database/schema 为 `matrix_code`。
- ORM 框架使用 MyBatis-Plus；新增正式仓储优先按 MyBatis-Plus 实现。
- H2 只允许用于测试兼容场景，不作为正式运行数据库。
- Flyway 负责建表和字段演进；新增表和新增字段必须写详细注释。
- 默认 `matrixcode.persistence.mode=file` 不创建 DataSource；只有 `matrixcode.persistence.mode=jdbc` 时才创建 MySQL DataSource 并启用正式仓储。
- 首个项目 Owner 初始化由 `MATRIXCODE_BOOTSTRAP_INITIAL_PROJECT_ENABLED` 控制，默认关闭；只在目标项目没有任何成员时执行，项目成员创建完成后应关闭。

## 生产启动门禁

生产启动使用专用脚本：

```bash
./scripts/run-production-local.sh .env.local
```

该脚本会先读取 `.env.local`，再强制校验以下上线开关：

- `MATRIXCODE_PRODUCTION_CHECK=true`
- `MATRIXCODE_PROTOCOL_CHECK=true`
- `MATRIXCODE_AUTH_REQUIRE_SA_TOKEN=true`
- `MATRIXCODE_AUTH_SESSION_STORE=redis`

脚本随后调用 `scripts/check-real-runtime.sh`，执行生产密钥、真实依赖连通性和协议级检查。生产启动默认不允许设置 `MATRIXCODE_SKIP_CONNECTIVITY_CHECK=true`；该跳过开关只允许脚本测试通过 `MATRIXCODE_PRODUCTION_ALLOW_SKIP_CONNECTIVITY=true` 放行。

上线前可先执行干运行，只验证门禁，不启动服务：

```bash
MATRIXCODE_PRODUCTION_DRY_RUN=true ./scripts/run-production-local.sh .env.local
```

脚本自身测试：

```bash
bash scripts/run-production-local-test.sh
```

## 生产部署资产

单机生产运行模板位于 `ops/`：

- `ops/systemd/matrixcode.service`：systemd 服务模板。
- `ops/nginx/matrixcode.conf`：Nginx 反向代理模板，包含 API、SSE 和健康检查路由。
- `ops/nginx/matrixcode-https.conf`：HTTPS 反向代理模板，包含域名、证书路径、跳转和安全响应头。
- `ops/bin/run-matrixcode-server.sh`：Jar 启动脚本，默认启动前执行真实运行预检。
- `ops/env/matrixcode.env.example`：生产环境变量示例。

静态校验和打包：

```bash
bash scripts/verify-production-deployment-assets.sh
bash scripts/verify-tls-assets.sh
bash scripts/build-production-server.sh
```

完整单机部署步骤见 `docs/deployment/production-runbook.md`，TLS 与真实域名入口见 `docs/deployment/tls-and-domain.md`。

## 生产备份与恢复

MySQL 业务库备份脚本：

```bash
MATRIXCODE_BACKUP_DRY_RUN=true ./scripts/backup-production-mysql.sh .env.local /tmp/matrixcode-backups
```

脚本测试：

```bash
bash scripts/backup-production-mysql-test.sh
bash scripts/prune-production-mysql-backups-test.sh
bash scripts/run-production-mysql-backup-test.sh
bash scripts/probe-production-health-test.sh
bash scripts/package-production-release-test.sh
bash scripts/smoke-production-release-test.sh
bash scripts/verify-remote-release-target-test.sh
bash scripts/generate-production-release-candidate-test.sh
bash scripts/install-production-release-test.sh
bash scripts/deploy-production-release-test.sh
bash scripts/verify-production-readiness.sh
```

完整恢复原则见 `docs/deployment/backup-restore.md`。恢复属于高风险操作，当前只提供手册步骤，不自动执行真实库恢复。

## 认证与公开入口约定

- 生产环境必须设置 `MATRIXCODE_AUTH_REQUIRE_SA_TOKEN=true`，普通 `/api/**` 业务接口会先经过全局 Sa-Token 登录态门禁，再进入控制器内的项目成员、角色和操作者一致性校验。
- 登录入口 `/api/projects/{projectId}/identity/auth/login` 和 bootstrap 签发入口 `/api/projects/{projectId}/identity/auth/actor-token` 保持公开，但必须携带正确的 `X-MatrixCode-Bootstrap-Token` 才能签发登录态。
- 登录态治理入口已接入项目成员权限：`POST /identity/auth/session/renew` 只允许当前项目有效成员续期；`GET /identity/auth/users/{userId}/sessions` 用户本人可看自己的会话，查看他人会话需要 OWNER、ADMIN 或 MAINTAINER，且目标用户也必须是当前项目有效成员；`POST /identity/auth/users/{userId}/sessions/kickout` 用户本人可踢下线自己的全部会话，踢下线他人需要项目管理权限，且不能治理非项目成员会话。
- 会话列表只返回 token 指纹、设备信息、创建时间和剩余有效期，不返回 Sa-Token 明文；登录、续期、退出和踢下线动作会写入用户级审计记录。
- 原生 `EventSource` 不能设置自定义 Header，事件流 `/api/projects/{projectId}/events/stream` 保留 URL 参数适配：桌面端会传 `actorUserId` 和 `actorToken`，服务端用 Sa-Token 反查登录用户后继续做项目成员校验。
- `/actuator/health`、`/actuator/info` 和静态资源不匹配 `/api/**`，不进入业务 API 门禁；上线前仍应通过网关或反向代理限制管理端点的暴露范围。

## 执行代理回调约定

- 执行代理心跳和结果上报配置 `MATRIXCODE_EXECUTION_AGENTS_SHARED_SECRET` 后必须携带 `X-MatrixCode-Agent-Token`。
- 执行代理凭据滚动轮换时，把新值写入 `MATRIXCODE_EXECUTION_AGENTS_SHARED_SECRET`，把仍在升级期的旧值写入 `MATRIXCODE_EXECUTION_AGENTS_PREVIOUS_SHARED_SECRETS`，多个旧值用英文逗号分隔；所有代理升级后必须清空旧值列表。
- 结果上报必须存在未过期的已认证心跳上下文，心跳 TTL 由 `MATRIXCODE_EXECUTION_AGENTS_HEARTBEAT_TTL_SECONDS` 控制，默认 120 秒。
- 同一 `taskId` 的首次结果写入后不可被不同状态、代理或摘要覆盖；完全相同的重复结果按幂等回调处理，不产生二次状态变更。
- 同一 `taskId` 携带不同内容再次上报会返回 HTTP 409，避免代理回调被回放覆盖既有任务结果。

## 运行桌面端

```bash
cd desktop
npm install
npm run dev
```

桌面端开发服务默认地址为 `http://localhost:5173`。

桌面端默认连接 `http://localhost:8080`。如需连接真实联调服务或其他地址，可以设置：

```bash
VITE_MATRIXCODE_SERVER_URL=http://localhost:18080 npm run dev
```

## 第二阶段角色工作台验证

服务端启动后可以访问项目工作台接口：

```bash
curl -sS http://localhost:8080/api/projects/demo/workbench
```

产品角色可以通过接口生成三份草稿文档：

```bash
curl -sS -X POST http://localhost:8080/api/projects/demo/roles/product/drafts \
  -H 'Content-Type: application/json' \
  -d '{"requirement":"支付失败后允许用户重新发起支付。"}'
```

桌面端启动后访问 `http://127.0.0.1:5173/`，依次切换产品、开发、测试、运维角色，提交各角色表单，右侧事件流、文档交接、Bug 队列和部署状态应同步更新。

第二阶段桌面工作台应覆盖以下角色动作：

- 产品：输入需求生成 PRD、验收标准和界面说明；冻结最新 PRD 草稿；提交验收通过或选择退回开发、测试。
- 开发：选择本地工作区，提交实现说明、自测结果、接口文档、数据库脚本和部署文档。
- 测试：记录 Bug，流转 Bug 状态，提交测试报告。
- 运维：配置环境地址、SSH 地址、部署说明、健康检查地址和回滚说明；当前阶段只记录授权信息，不触发真实 SSH 连接。

验收不通过时，工作台阶段会进入 `验收退回开发` 或 `验收退回测试`。多轮产品草稿同时存在时，桌面端会优先冻结创建时间最新的 PRD 草稿。

## 第三阶段模型网关验证

服务端启动后可以查看默认模型配置：

```bash
curl -sS http://localhost:8080/api/projects/demo/model-gateway/config
```

可以创建一次产品角色模型请求：

```bash
curl -sS -X POST http://localhost:8080/api/projects/demo/roles/product/model-requests \
  -H 'Content-Type: application/json' \
  -d '{"instruction":"支付失败后允许用户重新发起支付。","contextBlocks":[{"type":"PROJECT_RULE","summary":"保持中文输出","allowedByGate":true}]}'
```

连续执行两次后，第二次相同角色会话应出现稳定前缀缓存命中 token。工作台接口也会返回模型网关摘要：

```bash
curl -sS http://localhost:8080/api/projects/demo/workbench
```

真实联调入口启动后，默认角色推荐配置为：

- 产品：`qwen` / `qwen-plus`
- 开发：`deepseek` / `deepseek-chat`
- 测试：默认 `deepseek`，可切换到 `kimi` / `kimi-k2.5`
- 运维：默认 `qwen`，可切换到 `doubao` / `doubao-seed-1-6-flash-250615`

切换供应商时使用角色模型绑定接口；该接口会同步角色智能体配置，因此后续模型请求、配置页和 `recentRequests` 会保持一致：

```bash
curl -sS -X POST http://localhost:18080/api/projects/demo/roles/tester/model-binding \
  -H 'Content-Type: application/json' \
  -d '{"providerId":"kimi","model":"kimi-k2.5","currency":"CNY","cacheHitPerMillion":0.0,"cacheMissInputPerMillion":0.0,"outputPerMillion":0.0,"contextBudgetTokens":32000,"toolContractVersion":"tools-v1"}'

curl -sS -X POST http://localhost:18080/api/projects/demo/roles/operations/model-binding \
  -H 'Content-Type: application/json' \
  -d '{"providerId":"doubao","model":"doubao-seed-1-6-flash-250615","currency":"CNY","cacheHitPerMillion":0.0,"cacheMissInputPerMillion":0.0,"outputPerMillion":0.0,"contextBudgetTokens":32000,"toolContractVersion":"tools-v1"}'
```

Kimi `kimi-k*` 系列模型要求 `temperature=1`，服务端 OpenAI 兼容客户端会按供应商和模型自动适配；其他兼容供应商默认使用 `temperature=0.2`。

## 第四阶段本地执行代理验证

服务端启动后，可以先授权当前本地工作区：

```bash
WORKSPACE_JSON=$(curl -sS -X POST http://localhost:8080/api/projects/demo/local-execution/workspaces \
  -H 'Content-Type: application/json' \
  -d '{"name":"MatrixCode 工作区","rootPath":"/Users/Masons/Ai/Codex/MatrixCode/.worktrees/mvp-vertical-slice"}')
WORKSPACE_ID=$(node -e 'console.log(JSON.parse(process.argv[1]).id)' "$WORKSPACE_JSON")
```

`WORKSPACE_JSON` 中的 `status` 应为 `AUTHORIZED`，`WORKSPACE_ID` 是后续请求使用的 `workspaceId`。

读取授权工作区内的文本文件：

```bash
curl -sS -X POST http://localhost:8080/api/projects/demo/local-execution/files/read \
  -H 'Content-Type: application/json' \
  -d "{\"workspaceId\":\"${WORKSPACE_ID}\",\"relativePath\":\"docs/development/local-run.md\"}"
```

验证路径守卫会拦截路径逃逸：

```bash
curl -sS -X POST http://localhost:8080/api/projects/demo/local-execution/files/read \
  -H 'Content-Type: application/json' \
  -d "{\"workspaceId\":\"${WORKSPACE_ID}\",\"relativePath\":\"../secret.txt\"}"
```

预期返回 HTTP 400，错误信息包含 `路径不能离开授权工作区`。

提交需要审批的 SSH 命令：

```bash
curl -sS -X POST http://localhost:8080/api/projects/demo/local-execution/commands \
  -H 'Content-Type: application/json' \
  -d "{\"workspaceId\":\"${WORKSPACE_ID}\",\"actorId\":\"user-dev\",\"command\":\"ssh prod systemctl restart app\"}"
```

预期返回 `APPROVAL_PENDING` 和 `ASK`，服务端只记录审批与审计，不执行远程动作。

采集当前工作区 Git diff：

```bash
curl -sS -X POST http://localhost:8080/api/projects/demo/local-execution/git-diff \
  -H 'Content-Type: application/json' \
  -d "{\"workspaceId\":\"${WORKSPACE_ID}\"}"
```

在普通 Git 仓库或 `git worktree` 工作区中，响应的 `repository` 应为 `true`，`changedFiles` 和 `stat` 会返回当前未提交变更摘要。

查看工作台聚合结果：

```bash
curl -sS http://localhost:8080/api/projects/demo/workbench
```

工作台响应应包含 `localExecution` 字段，并聚合授权工作区、最近文件操作、命令审批、Git diff 和审计记录。

## 第五阶段人工审批执行闭环验证

服务端启动后，先授权当前本地工作区：

```bash
WORKSPACE_JSON=$(curl -sS -X POST http://localhost:8080/api/projects/demo/local-execution/workspaces \
  -H 'Content-Type: application/json' \
  -d '{"name":"MatrixCode 工作区","rootPath":"/Users/Masons/Ai/Codex/MatrixCode/.worktrees/mvp-vertical-slice"}')
WORKSPACE_ID=$(node -e 'console.log(JSON.parse(process.argv[1]).id)' "$WORKSPACE_JSON")
```

`WORKSPACE_JSON` 中的 `status` 应为 `AUTHORIZED`。

提交一条安全命令，确认命令会先进入人工审批：

```bash
TASK_JSON=$(curl -sS -X POST http://localhost:8080/api/projects/demo/local-execution/commands \
  -H 'Content-Type: application/json' \
  -d "{\"workspaceId\":\"${WORKSPACE_ID}\",\"actorId\":\"user-dev\",\"command\":\"git status\"}")
TASK_ID=$(node -e 'console.log(JSON.parse(process.argv[1]).taskId)' "$TASK_JSON")
```

`TASK_JSON` 中的 `status` 应为 `APPROVAL_PENDING`，`approvalDecision` 应为 `ASK`。

拒绝该任务：

```bash
curl -sS -X POST "http://localhost:8080/api/projects/demo/local-execution/commands/${TASK_ID}/approval" \
  -H 'Content-Type: application/json' \
  -d '{"actorId":"user-reviewer","decision":"DENY","note":"运行态验证拒绝"}'
```

响应应包含 `status: DENIED`、`approvalDecision: DENY` 和 `approverId: user-reviewer`。

重复审批同一任务：

```bash
curl -sS -i -X POST "http://localhost:8080/api/projects/demo/local-execution/commands/${TASK_ID}/approval" \
  -H 'Content-Type: application/json' \
  -d '{"actorId":"user-reviewer","decision":"ALLOW","note":"重复处理验证"}'
```

预期返回 HTTP 400，错误信息包含 `任务已完成审批，不能重复处理`。

批准一条安全命令，确认审批通过后进入本地执行队列：

```bash
PWD_TASK_JSON=$(curl -sS -X POST http://localhost:8080/api/projects/demo/local-execution/commands \
  -H 'Content-Type: application/json' \
  -d "{\"workspaceId\":\"${WORKSPACE_ID}\",\"actorId\":\"user-dev\",\"command\":\"pwd\"}")
PWD_TASK_ID=$(node -e 'console.log(JSON.parse(process.argv[1]).taskId)' "$PWD_TASK_JSON")
curl -sS -X POST "http://localhost:8080/api/projects/demo/local-execution/commands/${PWD_TASK_ID}/approval" \
  -H 'Content-Type: application/json' \
  -d '{"actorId":"user-reviewer","decision":"ALLOW","note":"运行态验证允许执行"}'
```

第七阶段起，审批响应应包含 `approvalDecision: ALLOW` 和 `status: QUEUED`。最终执行结果不再由审批接口同步返回，请通过工作台摘要、任务日志或下方第七阶段长任务队列验证步骤查询。

提交一条 SSH 命令并尝试批准：

```bash
SSH_TASK_JSON=$(curl -sS -X POST http://localhost:8080/api/projects/demo/local-execution/commands \
  -H 'Content-Type: application/json' \
  -d "{\"workspaceId\":\"${WORKSPACE_ID}\",\"actorId\":\"user-ops\",\"command\":\"ssh prod systemctl restart app\"}")
SSH_TASK_ID=$(node -e 'console.log(JSON.parse(process.argv[1]).taskId)' "$SSH_TASK_JSON")
curl -sS -X POST "http://localhost:8080/api/projects/demo/local-execution/commands/${SSH_TASK_ID}/approval" \
  -H 'Content-Type: application/json' \
  -d '{"actorId":"user-reviewer","decision":"ALLOW","note":"验证 SSH 不执行"}'
```

预期返回 `DENIED`，标准错误摘要或 `safetyRejectionReason` 包含 `该命令不在第五阶段可批准执行范围内`。即使人工批准，服务端也不会启动远程命令。

查看工作台聚合结果：

```bash
curl -sS http://localhost:8080/api/projects/demo/workbench
```

工作台响应中的 `localExecution.recentTasks` 应包含最新审批结果，`recentAuditRecords` 应包含 `ASK`、`ALLOW` 或 `DENY` 审计记录。

## 第六阶段部署健康检查与运维记录验证

服务端启动后，先配置部署目标：

```bash
TARGET_JSON=$(curl -sS -X POST http://localhost:8080/api/projects/demo/deployments/targets \
  -H 'Content-Type: application/json' \
  -d '{"environmentName":"本地验证环境","environmentUrl":"http://localhost:8080","sshAddress":"deploy@localhost","deployNote":"只记录部署说明，不执行 SSH","healthCheckUrl":"http://localhost:8080/actuator/health","rollbackNote":"只记录回滚说明，不执行远程命令"}')
TARGET_ID=$(node -e 'console.log(JSON.parse(process.argv[1]).id)' "$TARGET_JSON")
```

运行健康检查：

```bash
curl -sS -X POST "http://localhost:8080/api/projects/demo/deployments/targets/${TARGET_ID}/health-checks" \
  -H 'Content-Type: application/json' \
  -d '{"actorId":"user-ops"}'
```

响应应包含 `HEALTHY`、`HTTP 200` 和 `durationMillis`。

记录部署和回滚：

```bash
curl -sS -X POST "http://localhost:8080/api/projects/demo/deployments/targets/${TARGET_ID}/operations" \
  -H 'Content-Type: application/json' \
  -d '{"actorId":"user-ops","type":"DEPLOYMENT","status":"SUCCEEDED","note":"本地验证部署记录"}'

curl -sS -X POST "http://localhost:8080/api/projects/demo/deployments/targets/${TARGET_ID}/operations" \
  -H 'Content-Type: application/json' \
  -d '{"actorId":"user-ops","type":"ROLLBACK","status":"RECORDED","note":"本地验证回滚记录"}'
```

查看工作台聚合结果：

```bash
curl -sS http://localhost:8080/api/projects/demo/workbench
```

工作台响应中的 `deploymentRuntimeSummaries` 应包含最新健康检查、部署记录和回滚记录。以上操作不会执行 SSH、不会运行远程部署脚本，也不会读取凭证或保存健康检查响应体。

## 第七阶段本地长任务队列验证

服务端启动后，先授权当前本地工作区：

```bash
WORKSPACE_JSON=$(curl -sS -X POST http://localhost:8080/api/projects/demo/local-execution/workspaces \
  -H 'Content-Type: application/json' \
  -d '{"name":"MatrixCode 工作区","rootPath":"/Users/Masons/Ai/Codex/MatrixCode/.worktrees/mvp-vertical-slice"}')
WORKSPACE_ID=$(node -e 'console.log(JSON.parse(process.argv[1]).id)' "$WORKSPACE_JSON")
```

`WORKSPACE_JSON` 中的 `status` 应为 `AUTHORIZED`。

提交一个需要人工审批的长任务并批准执行：

```bash
TASK_JSON=$(curl -sS -X POST http://localhost:8080/api/projects/demo/local-execution/commands \
  -H 'Content-Type: application/json' \
  -d "{\"workspaceId\":\"${WORKSPACE_ID}\",\"actorId\":\"user-dev\",\"command\":\"sleep 20\"}")
TASK_ID=$(node -e 'console.log(JSON.parse(process.argv[1]).taskId)' "$TASK_JSON")

curl -sS -X POST "http://localhost:8080/api/projects/demo/local-execution/commands/${TASK_ID}/approval" \
  -H 'Content-Type: application/json' \
  -d '{"actorId":"user-reviewer","decision":"ALLOW","note":"运行态验证允许执行"}'
```

审批响应应为 `QUEUED`。随后查询工作台可看到该任务处于 `QUEUED`、`RUNNING`，或已经写入最近任务日志：

```bash
curl -sS http://localhost:8080/api/projects/demo/workbench
```

`localExecution.activeTasks` 或 `localExecution.recentTaskLogs` 应包含该任务。

取消任务并查询日志：

```bash
curl -sS -X POST "http://localhost:8080/api/projects/demo/local-execution/commands/${TASK_ID}/cancel" \
  -H 'Content-Type: application/json' \
  -d '{"actorId":"user-reviewer","note":"运行态验证取消"}'

curl -sS "http://localhost:8080/api/projects/demo/local-execution/commands/${TASK_ID}/logs"
```

取消响应应包含 `CANCELED` 和 `canceledBy=user-reviewer`。日志响应应包含 `SYSTEM` 日志。

提交 SSH 命令并尝试批准：

```bash
SSH_TASK_JSON=$(curl -sS -X POST http://localhost:8080/api/projects/demo/local-execution/commands \
  -H 'Content-Type: application/json' \
  -d "{\"workspaceId\":\"${WORKSPACE_ID}\",\"actorId\":\"user-ops\",\"command\":\"ssh prod systemctl restart app\"}")
SSH_TASK_ID=$(node -e 'console.log(JSON.parse(process.argv[1]).taskId)' "$SSH_TASK_JSON")

curl -sS -X POST "http://localhost:8080/api/projects/demo/local-execution/commands/${SSH_TASK_ID}/approval" \
  -H 'Content-Type: application/json' \
  -d '{"actorId":"user-reviewer","decision":"ALLOW","note":"验证 SSH 不入队"}'
```

响应应为 `DENIED`，`safetyRejectionReason` 应包含 `该命令不在第五阶段可批准执行范围内`。SSH、部署、凭证和危险命令即使人工批准，也不会进入长任务队列。

## 第八阶段本地任务运行态加固验证

第八阶段在第七阶段长任务队列基础上增加运行中任务自动刷新。服务端和桌面端启动后，先按第七阶段步骤授权当前本地工作区，再提交一个会自然结束的短长任务：

```bash
TASK_JSON=$(curl -sS -X POST http://localhost:8080/api/projects/demo/local-execution/commands \
  -H 'Content-Type: application/json' \
  -d "{\"workspaceId\":\"${WORKSPACE_ID}\",\"actorId\":\"user-dev\",\"command\":\"sleep 3\"}")
TASK_ID=$(node -e 'console.log(JSON.parse(process.argv[1]).taskId)' "$TASK_JSON")

curl -sS -X POST "http://localhost:8080/api/projects/demo/local-execution/commands/${TASK_ID}/approval" \
  -H 'Content-Type: application/json' \
  -d '{"actorId":"user-reviewer","decision":"ALLOW","note":"验证自动刷新"}'
```

打开桌面端 `http://127.0.0.1:5173/`，右侧“本地执行代理”卡片应先显示 `排队中` 或 `运行中`。任务结束后，无需手动点击其他按钮，卡片应在下一次自动同步后显示 `成功`，并展示 `任务运行完成，退出码：0` 系统日志。

也可以用工作台接口核对终态：

```bash
sleep 5
curl -sS http://localhost:8080/api/projects/demo/workbench
```

响应中的 `localExecution.recentTasks` 应包含该任务，状态应为 `SUCCESS`，`localExecution.recentTaskLogs` 应包含完成日志。服务端内部会有界保留已接收任务编号，避免长时间运行时去重标记无界增长。

## 第九阶段 Docker Compose 演示环境运行态验证

第九阶段在部署目标基础上增加受控 Compose 演示环境。服务端和桌面端启动后，先准备一个本地 Compose 文件：

```bash
mkdir -p /tmp/matrixcode-compose-demo
printf '%s\n' \
  'services:' \
  '  web:' \
  '    image: nginx:alpine' \
  '    ports:' \
  '      - "18080:80"' \
  > /tmp/matrixcode-compose-demo/compose.yml
```

授权该目录作为本地工作区：

```bash
WORKSPACE_JSON=$(curl -sS -X POST http://localhost:8080/api/projects/demo/local-execution/workspaces \
  -H 'Content-Type: application/json' \
  -d '{"name":"Compose 演示工作区","rootPath":"/tmp/matrixcode-compose-demo"}')
WORKSPACE_ID=$(node -e 'console.log(JSON.parse(process.argv[1]).id)' "$WORKSPACE_JSON")
```

配置部署目标：

```bash
TARGET_JSON=$(curl -sS -X POST http://localhost:8080/api/projects/demo/deployments/targets \
  -H 'Content-Type: application/json' \
  -d '{"environmentName":"Compose 演示环境","environmentUrl":"http://127.0.0.1:18080","sshAddress":"deploy@local","deployNote":"本地 Compose 演示，不执行 SSH","healthCheckUrl":"http://127.0.0.1:18080","rollbackNote":"停止本地 Compose 服务"}')
TARGET_ID=$(node -e 'console.log(JSON.parse(process.argv[1]).id)' "$TARGET_JSON")
```

登记 Compose 演示环境：

```bash
COMPOSE_JSON=$(curl -sS -X POST "http://localhost:8080/api/projects/demo/deployments/targets/${TARGET_ID}/compose-environments" \
  -H 'Content-Type: application/json' \
  -d "{\"workspaceId\":\"${WORKSPACE_ID}\",\"composeFilePath\":\"compose.yml\",\"projectName\":\"matrixcode-demo\",\"serviceName\":\"web\"}")
COMPOSE_ID=$(node -e 'console.log(JSON.parse(process.argv[1]).id)' "$COMPOSE_JSON")
```

响应应包含 `status: CONFIGURED`、`composeFilePath: compose.yml`、`projectName: matrixcode-demo` 和 `serviceName: web`。

校验配置、启动演示、采集日志、停止演示：

```bash
curl -sS -X POST "http://localhost:8080/api/projects/demo/compose-environments/${COMPOSE_ID}/validate" \
  -H 'Content-Type: application/json' \
  -d '{"actorId":"user-ops"}'

curl -sS -X POST "http://localhost:8080/api/projects/demo/compose-environments/${COMPOSE_ID}/start" \
  -H 'Content-Type: application/json' \
  -d '{"actorId":"user-ops"}'

curl -sS -X POST "http://localhost:8080/api/projects/demo/compose-environments/${COMPOSE_ID}/logs" \
  -H 'Content-Type: application/json' \
  -d '{"actorId":"user-ops"}'

curl -sS -X POST "http://localhost:8080/api/projects/demo/compose-environments/${COMPOSE_ID}/stop" \
  -H 'Content-Type: application/json' \
  -d '{"actorId":"user-ops"}'
```

如果本机 Docker CLI 和 Docker Compose 可用，动作会返回 `SUCCEEDED`，日志采样会包含最近容器日志。如果 Docker 不可用，动作会返回 `FAILED`，摘要包含 `Docker Compose 不可用`；如果 Docker CLI、镜像拉取或凭证助手长时间无响应，动作会返回 `FAILED`，摘要包含 `Docker Compose 命令超时`，并清理相关子进程。这些失败路径仍可验证服务端不会崩溃，工作台会展示失败记录。

查看工作台聚合结果：

```bash
curl -sS http://localhost:8080/api/projects/demo/workbench
```

响应中的 `composeEnvironments` 应包含该环境，`composeRuntimeViews` 应包含最近一次 Compose 操作。桌面端打开 `http://127.0.0.1:5173/` 后，运维面板应显示「Compose 演示环境」，右侧指标栏应显示「Compose 运行态」。

## 第十阶段运行态实时同步验证

第十阶段在第八阶段轮询兜底和第九阶段 Compose 运行态基础上接入项目 SSE 事件流。服务端和桌面端启动后，先按第八阶段提交 `sleep 3` 本地任务并批准执行。桌面端右侧「本地执行代理」应在 `LOCAL_COMMAND_COMPLETED` 事件到达后自动显示成功状态，并展示 `任务运行完成，退出码：0` 系统日志；如果当前浏览器不支持 SSE，第八阶段的 2 秒轮询仍会刷新终态。

按第九阶段登记 Compose 演示环境并触发启动、校验、日志采集或停止。桌面端右侧「Compose 运行态」应在 `COMPOSE_OPERATION_RECORDED` 事件到达后自动刷新，展示最新成功、失败或超时摘要。本机 Docker CLI、镜像拉取或凭证助手不可用时，失败摘要仍应自动出现在右侧运行态卡片中。

## 第十一阶段运行态提醒中心验证

服务端和桌面端启动后，提交一条需要审批的本地命令，例如 `git status`。桌面端左侧运维角色应显示待审批数量徽标，顶部应显示「需要审批本地命令」提醒，右侧「运行态提醒」卡片应列出该提醒。

关闭顶部提醒后，右侧「本地执行代理」里的审批按钮应仍然可用，右侧「运行态提醒」列表也应保留提醒记录。批准一条 `sleep 3` 命令并等待完成，页面应在 SSE 刷新后显示「本地命令执行成功」提醒。

触发 Compose 启动失败或超时后，右侧「运行态提醒」应显示「Compose 动作失败」和失败摘要。浏览器控制台不应出现 error。

## 第十二阶段运行态提醒收件箱验证

服务端和桌面端启动后，先按第四阶段授权当前工作区，再提交一条 `git status` 本地命令。访问工作台接口：

```bash
curl -sS http://localhost:8080/api/projects/demo/workbench
```

响应应包含 `runtimeNotifications` 字段，最近一条待审批提醒的 `id` 形如 `approval:<taskId>`，`readAt` 为 `null`。

桌面端打开 `http://127.0.0.1:5173/` 后，顶部应显示「需要审批本地命令」，右侧「运行态提醒」列表应显示同一提醒并标记为「未读」。点击顶部关闭按钮后，桌面端会调用：

```text
POST /api/projects/demo/runtime-notifications/<notificationId>/read
```

刷新页面后，同一提醒不应再次出现在顶部；右侧列表仍应保留该提醒，并标记为「已读」。随后批准一条 `sleep 3` 命令并等待完成，新的「本地命令执行成功」提醒应作为「未读」提醒出现。收到 `RUNTIME_NOTIFICATION_READ`、本地任务或 Compose 运行态 SSE 事件时，桌面端应自动刷新工作台。

## 第十三阶段运行态提醒操作中心验证

服务端和桌面端启动后，先按第四阶段授权当前工作区，再提交一条 `git status` 本地命令。桌面端顶部应显示「需要审批本地命令」，右侧「运行态提醒」卡片应显示「未读 1」，并提供「全部」「未读」两个视图。

继续批准一条 `sleep 3` 命令并等待执行完成，右侧「运行态提醒」未读数量应增加。点击「未读」后，列表只展示未读提醒；点击「全部已读」后，桌面端会调用：

```text
POST /api/projects/demo/runtime-notifications/read-all
```

批量已读完成后，顶部提醒应消失，右侧未读数量变为 0。切回「全部」后，原提醒仍应保留在列表中，并标记为「已读」。刷新页面后，已读状态不应回退；浏览器控制台不应出现 error。

## 第十四阶段运行态提醒轻量持久化验证

服务端默认把运行态提醒快照写入 `.matrixcode/runtime-notifications.json`，该目录只用于本地运行数据，不提交到 Git。如需指定存储文件路径，可以设置：

```bash
MATRIXCODE_RUNTIME_NOTIFICATIONS_STORAGE_PATH=/tmp/matrixcode-runtime-notifications.json /Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -pl server spring-boot:run
```

服务端和桌面端启动后，先按第四阶段授权当前工作区，再准备至少两条运行态提醒：提交一条待审批 `git status`，再批准一条 `sleep 3` 并等待完成。点击「全部已读」后，停止服务端，再用同一个存储路径重启服务端。

桌面端刷新后，顶部不应重新出现已读提醒；右侧「运行态提醒」应保留原有记录，并继续显示「已读」状态和「未读 0」。浏览器控制台不应出现 error。

## 第十五阶段本地执行状态轻量持久化验证

服务端默认把本地执行状态快照写入 `.matrixcode/local-execution.json`，该目录只用于本地运行数据，不提交到 Git。快照包含授权工作区、本地任务、任务日志和审批审计记录；文件读取记录和 Git diff 摘要仍只保留在当前进程内。

如需指定存储文件路径，可以设置：

```bash
MATRIXCODE_LOCAL_EXECUTION_STORAGE_PATH=/tmp/matrixcode-local-execution.json \
MATRIXCODE_RUNTIME_NOTIFICATIONS_STORAGE_PATH=/tmp/matrixcode-runtime-notifications.json \
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -pl server spring-boot:run
```

服务端和桌面端启动后，先按第四阶段授权当前工作区，再提交一条待审批 `git status` 命令。停止服务端后，用同一个 `MATRIXCODE_LOCAL_EXECUTION_STORAGE_PATH` 重启服务端并刷新桌面端。

刷新后，右侧「本地执行代理」应保留授权工作区、待审批任务和审计记录；继续拒绝该待审批任务应返回 `DENIED`，工作台摘要中的 `recentAuditRecords` 应追加 `DENY` 记录。

如果服务端关闭时存在 `QUEUED` 或 `RUNNING` 任务，重启后这些任务会恢复为 `CANCELED`，并追加 `服务重启后任务已停止` 系统日志。`APPROVAL_PENDING` 任务会继续保留为待审批状态，便于用户重启后继续批准或拒绝。

## 第十六阶段项目工作台状态轻量持久化验证

服务端默认把项目工作台核心业务状态快照写入 `.matrixcode/workbench-state.json`，该目录只用于本地运行数据，不提交到 Git。快照包含文档、Bug、部署目标、部署记录、健康检查、Compose 环境、模型网关请求、项目事件、文件操作、Git diff、工作流和最近验收投影。

本地执行任务状态仍写入 `.matrixcode/local-execution.json`，运行态提醒仍写入 `.matrixcode/runtime-notifications.json`。如需完整验证重启恢复，建议三份快照都显式指定到临时路径：

```bash
MATRIXCODE_WORKBENCH_STATE_STORAGE_PATH=/tmp/matrixcode-workbench-state.json \
MATRIXCODE_LOCAL_EXECUTION_STORAGE_PATH=/tmp/matrixcode-local-execution.json \
MATRIXCODE_RUNTIME_NOTIFICATIONS_STORAGE_PATH=/tmp/matrixcode-runtime-notifications.json \
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -pl server spring-boot:run
```

服务端和桌面端启动后，按前面阶段准备一组完整工作台状态：授权工作区并读取文件、采集 Git diff、生成并冻结产品文档、提交开发交付、创建并关闭 Bug、提交测试报告、提交一次验收退回、配置部署目标、运行健康检查、记录部署操作、登记 Compose 环境，并发起一次模型网关请求。

停止服务端后，用同一组 `MATRIXCODE_*_STORAGE_PATH` 环境变量重启服务端并刷新桌面端。工作台应恢复到重启前阶段，例如 `验收退回测试`；产品文档冻结状态、Bug 关闭状态、部署目标、健康检查、部署记录、Compose 环境、模型请求、事件流、文件操作和最近 Git diff 都应继续可见。

也可以直接用工作台接口核对：

```bash
curl -sS http://localhost:8080/api/projects/demo/workbench
```

响应应包含 `documents`、`bugs`、`deploymentTargets`、`composeEnvironments`、`modelGateway`、`events`、`localExecution.recentFileOperations` 和 `localExecution.recentGitDiff` 等字段。若快照文件不存在、损坏或版本不兼容，服务端会以空快照启动，不会阻塞应用启动。

## 第十七阶段 JDBC 快照持久化验证

第十七阶段新增可选 JDBC 快照模式。默认配置仍是文件模式，继续使用第十四到十六阶段的 `.matrixcode/*.json` 快照；该阶段的 `matrixcode_state_snapshots` 说明是历史验证口径。

第 150 阶段后，JDBC 主路径不再向 `matrixcode_state_snapshots` 写入 `runtime-notifications`、`local-execution` 或 `workbench-state` 切片。旧快照表只作为历史兼容和空表回填来源；只有显式设置 `MATRIXCODE_PERSISTENCE_JDBC_LEGACY_SNAPSHOT_WRITES_ENABLED=true` 时，`JdbcWorkbenchStateStore` 才允许写入旧 `workbench-state` 切片。

先启动本地 MySQL：

```bash
docker compose up -d mysql
```

再以 JDBC 模式启动服务端：

```bash
MATRIXCODE_PERSISTENCE_MODE=jdbc \
MATRIXCODE_PERSISTENCE_JDBC_URL='jdbc:mysql://localhost:3306/matrix_code?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=5000&socketTimeout=30000' \
MATRIXCODE_PERSISTENCE_JDBC_USERNAME=matrixcode \
MATRIXCODE_PERSISTENCE_JDBC_PASSWORD=matrixcode \
MATRIXCODE_PERSISTENCE_JDBC_LEGACY_SNAPSHOT_WRITES_ENABLED=false \
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -pl server spring-boot:run
```

服务端和桌面端启动后，按第十六阶段准备完整工作台状态，并至少访问一次：

```bash
curl -sS http://localhost:8080/api/projects/demo/workbench
```

停止服务端后，用同一组 JDBC 环境变量重启服务端并刷新桌面端。工作台应恢复重启前状态；默认 `.matrixcode/runtime-notifications.json`、`.matrixcode/local-execution.json` 和 `.matrixcode/workbench-state.json` 不应被写入。

可以查询数据库确认历史快照兼容表没有新增主路径切片：

```bash
docker compose exec -T mysql mysql -umatrixcode -pmatrixcode matrix_code \
  -e "select slice_key, version from matrixcode_state_snapshots order by slice_key;"
```

当前主路径预期不再新增 `runtime-notifications`、`local-execution` 和 `workbench-state` 三行。若历史环境曾写入这些切片，正式仓储为空时会读取并回填到对应正式表；迁移完成后建议保持 `MATRIXCODE_PERSISTENCE_JDBC_LEGACY_SNAPSHOT_WRITES_ENABLED=false`。

## 第十八阶段 MySQL Flyway 领域表迁移验证

第十八阶段新增 Flyway 迁移基础和第一批 MySQL 领域表。默认仍是文件模式，不会连接数据库，也不会执行 Flyway；只有同时设置 `MATRIXCODE_PERSISTENCE_MODE=jdbc` 和 `MATRIXCODE_PERSISTENCE_JDBC_MIGRATE_ON_STARTUP=true` 时，服务端启动才会执行 `classpath:db/migration` 下的迁移脚本。

当前本地依赖基线：

- 业务数据：MySQL。
- 向量数据库：Milvus，等上下文检索阶段需要时接入。
- 缓存：Redis，已可通过 `MATRIXCODE_AUTH_SESSION_STORE=redis` 承载 Sa-Token 分布式会话；presence 和热点配置仍按业务需要继续接入。
- 消息：RocketMQ，等跨节点事件流或异步任务阶段需要时接入。

启动 MySQL：

```bash
docker compose up -d mysql
```

开启启动迁移：

```bash
MATRIXCODE_PERSISTENCE_MODE=jdbc \
MATRIXCODE_PERSISTENCE_JDBC_URL='jdbc:mysql://localhost:3306/matrix_code?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=5000&socketTimeout=30000' \
MATRIXCODE_PERSISTENCE_JDBC_USERNAME=matrixcode \
MATRIXCODE_PERSISTENCE_JDBC_PASSWORD=matrixcode \
MATRIXCODE_PERSISTENCE_JDBC_MIGRATE_ON_STARTUP=true \
MATRIXCODE_PERSISTENCE_JDBC_CREATE_DATABASE_IF_MISSING=true \
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -pl server spring-boot:run
```

如果本机没有 `mysql` CLI，只要 MySQL 账号具备 `create database` 权限，也可以开启
`MATRIXCODE_PERSISTENCE_JDBC_CREATE_DATABASE_IF_MISSING=true`。服务端启动早期会先通过 JDBC
创建 URL 中的数据库，再执行 Flyway 迁移；脚本 `scripts/run-real-local.sh` 也走同一套逻辑。

确认 Flyway 历史和领域表：

```bash
docker compose exec -T mysql mysql -umatrixcode -pmatrixcode matrix_code \
  -e "select version, description, success from flyway_schema_history order by installed_rank;"
```

```bash
docker compose exec -T mysql mysql -umatrixcode -pmatrixcode matrix_code \
  -e "show tables like 'matrixcode_%';"
```

预期至少包含用户、项目、项目成员、角色智能体配置、协作会话、文档、Bug、部署目标、运行态提醒、本地执行任务、审批审计和项目事件相关表。当前阶段只建表，不把业务服务仓储从 JSON 快照切换到关系表。真实 MySQL 地址由最终部署时提供后，再用同一组环境变量回归验证。

## 第十九阶段角色智能体配置中心验证

第十九阶段为四个角色智能体提供可读写配置中心。当前覆盖系统提示词、用户提示词模板、模型供应商、模型名、工具契约、缓存策略、动态后缀策略、缓存作用域策略、主题色、字体、字号、显示顺序和启用状态。

缓存作用域策略用于控制同一角色模型请求如何复用供应商侧和本地估算侧的缓存归因：

- `provider-model`：默认值，按项目、角色、供应商和模型隔离，最稳妥。
- `provider-role`：同项目、同角色、同供应商跨模型复用，适合手动切换同供应商模型变体时压低稳定前缀成本。
- `project-role`：同项目、同角色最大复用，只建议在确认供应商缓存语义和成本归因后使用。

读取当前项目角色智能体配置：

```bash
curl -sS http://localhost:8080/api/projects/demo/role-agent-configs
```

更新开发智能体配置：

```bash
curl -sS -X PUT http://localhost:8080/api/projects/demo/role-agent-configs/developer \
  -H 'Content-Type: application/json' \
  -d '{
    "displayName": "开发智能体 Pro",
    "agentKind": "coding",
    "providerId": "local-deterministic",
    "model": "matrixcode-local-developer-pro",
    "toolContractVersion": "tools-v2",
    "cachePolicyId": "stable-platform-prefix-v1",
    "volatileSuffixStrategy": "role-prompt-and-dynamic-context",
    "cacheScopeStrategy": "provider-model",
    "systemPrompt": "你是开发编码智能体，必须先读代码再修改。",
    "userPromptTemplate": "请基于以下任务输出计划并执行：{{instruction}}",
    "themeColor": "#0f766e",
    "fontFamily": "Inter",
    "fontSize": 15,
    "sortOrder": 2,
    "enabled": true
  }'
```

桌面端启动后，打开配置按钮并进入「角色」页，会显示产品、开发、测试、运维四个智能体。修改开发智能体的模型、提示词、缓存策略、缓存作用域和主题色后点击保存，应调用同一接口并刷新配置列表。

当前持久化边界：

- 默认文件模式：配置仍可随工作台快照写入 `.matrixcode/workbench-state.json`。
- JDBC 模式：第 46 阶段后角色配置已写入 `matrixcode_role_agent_configs`，旧 `workbench-state` 只作为正式表为空时的回填来源。
- 生产默认保持 `MATRIXCODE_PERSISTENCE_JDBC_LEGACY_SNAPSHOT_WRITES_ENABLED=false`，避免新增旧 `workbench-state` 主快照。

## 第四十二阶段 Agent 运行记录正式仓储验证

第四十二阶段新增 Agent Runtime 正式表和 MyBatis-Plus 仓储。该阶段以后，角色智能体运行主状态和事件时间线应写入 MySQL：

- `matrixcode_agent_runs`
- `matrixcode_agent_run_events`

定向验证：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server \
  -Dtest=DatabaseMigrationServiceTest,DatabaseMigrationCommentPolicyTest,MybatisPlusAgentRuntimeRepositoryTest test
```

真实环境验证：

```bash
set -a
source .env.local
set +a
./scripts/check-real-runtime.sh
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server \
  -Dmatrixcode.real-runtime-test=true -Dtest=RealRuntimeIntegrationTest test
```

预期结果：

- Flyway 将真实 MySQL `matrix_code` 迁移到 v42.1 或确认已是 v42.1。
- `RealRuntimeIntegrationTest` 能通过 MyBatis-Plus 写入并读取 Agent 运行记录和运行事件。
- Redis、RocketMQ 端口可达，千问 embedding 和 Milvus 写入召回通过。

## RocketMQ 项目事件中继

第 159 阶段后，项目事件流支持通过 RocketMQ 做跨节点中继。默认仍关闭，避免本地单实例开发或生产单实例误连接 MQ；需要多实例实时协作且真实协议门禁通过时开启：

```bash
MATRIXCODE_ROCKETMQ_EVENT_RELAY_ENABLED=true
MATRIXCODE_ROCKETMQ_PROTOCOL_CHECK=true
MATRIXCODE_ROCKETMQ_NAME_SERVER=127.0.0.1:9876
MATRIXCODE_ROCKETMQ_TOPIC_PREFIX=matrixcode
MATRIXCODE_ROCKETMQ_EVENT_RELAY_TOPIC_SUFFIX=project-events
MATRIXCODE_ROCKETMQ_EVENT_RELAY_TAG=project-event
```

TCP 检查只能证明 NameServer 端口可达。上线前需要额外打开协议级收发门禁：

```bash
MATRIXCODE_PROTOCOL_CHECK=true MATRIXCODE_ROCKETMQ_PROTOCOL_CHECK=true ./scripts/check-real-runtime.sh .env.local
```

该门禁会使用 ProjectEvent 的真实 RocketMQ 消息格式向 `matrixcode-project-events` 发送一条低敏事件，并等待消费者收到同一事件。如果失败，优先检查 broker 对外广播地址、`brokerIP1`、10911 端口防火墙、代理和 Topic 权限。

## 生产上线门禁

日常快速检查仍可只跑 TCP 与配置校验：

```bash
./scripts/check-real-runtime.sh .env.local
```

上线前需要开启生产硬门禁和协议级检查：

```bash
MATRIXCODE_PRODUCTION_CHECK=true MATRIXCODE_PROTOCOL_CHECK=true ./scripts/check-real-runtime.sh .env.local
```

生产硬门禁要求：

- `MATRIXCODE_AUTH_REQUIRE_SA_TOKEN=true`
- `MATRIXCODE_AUTH_SESSION_STORE=redis`
- `MATRIXCODE_AUTH_ACTOR_TOKEN_SECRET`、`MATRIXCODE_AUTH_BOOTSTRAP_TOKEN` 和 `MATRIXCODE_EXECUTION_AGENTS_SHARED_SECRET` 必须为真实值
- `MATRIXCODE_AUTH_SESSION_REDIS_KEY_PREFIX`、`MATRIXCODE_REDIS_HOST` 和 `MATRIXCODE_REDIS_PORT` 必须配置
- `MATRIXCODE_PROTOCOL_CHECK=true`，用于复验真实 MySQL/Flyway、Milvus 写入召回和 Sa-Token Redis Session 写读删

## Agent Runtime Worker 调度器

第 131 阶段后，后端提供可配置的 Agent Runtime Worker 后台调度器。默认关闭，避免本地开发误消费队列；生产环境确认 Sa-Token、项目成员、模型供应商和审计链路可用后再开启。

```bash
MATRIXCODE_AGENT_WORKER_SCHEDULER_ENABLED=true
MATRIXCODE_AGENT_WORKER_SCHEDULER_PROJECT_ID=demo
MATRIXCODE_AGENT_WORKER_SCHEDULER_WORKER_ID=matrixcode-worker
MATRIXCODE_AGENT_WORKER_SCHEDULER_EXECUTE_MODEL_REQUEST=true
MATRIXCODE_AGENT_WORKER_SCHEDULER_FIXED_DELAY_MS=10000
```

调度器只做过期租约回收、`QUEUED` 运行认领和可选受控模型步骤，不会自动执行命令、写文件、应用 Patch 或批准审批项。

## 运行 Tauri 桌面壳

```bash
cd desktop
npm run tauri:dev
```

## 构建 Tauri 安装包

```bash
cd desktop
npm run tauri:build
```

Tauri 打包需要本机 Rust 工具链和对应平台的系统依赖；macOS 和 Windows 的安装包由 Tauri 按当前平台生成。

## 常用验证命令

```bash
docker compose config
```

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

```bash
cd desktop
npm test
npm run build
npm run tauri:build -- --help
```
