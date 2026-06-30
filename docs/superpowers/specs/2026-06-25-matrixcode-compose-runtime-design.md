# MatrixCode 第九阶段 Docker Compose 演示环境运行态设计规格

## 背景

前八个阶段已经完成角色工作台、模型网关、本地执行代理、人工审批、部署健康检查、本地长任务队列和任务运行态自动刷新。当前运维角色可以记录部署目标、运行健康检查、登记部署和回滚结果，但还不能把一个本地演示环境和部署目标绑定起来，也不能在安全边界内启动、停止或采集 Compose 服务日志。

第九阶段把运维目标扩展为受控 Docker Compose 演示环境运行态。系统需要允许用户在已授权本地工作区内登记 Compose 文件、项目名和服务名，然后通过专用服务执行配置校验、启动、停止和日志采样。该能力不复用普通本地命令审批通道，因为第五阶段明确禁止 Docker、远程部署和基础设施命令通过任意 shell 字符串执行。

## 范围

第九阶段实现以下能力：

- 为部署目标登记一个 Docker Compose 演示环境。
- Compose 文件必须位于已授权本地工作区内，并且只能使用 `.yml` 或 `.yaml` 后缀。
- Compose 项目名和服务名必须是有限字符集，避免把任意 shell 片段拼进执行流程。
- 服务端提供配置校验、启动、停止和日志采样动作。
- Compose 动作通过专用运行时客户端执行参数化命令，不接收任意命令字符串。
- Docker 或 Docker Compose 不可用时，动作记录为失败，并把失败摘要展示到工作台。
- 工作台聚合 Compose 环境和最近一次 Compose 操作。
- 桌面端运维面板可以登记 Compose 环境、触发校验、启动、停止和日志采样。
- 右侧运行指标展示 Compose 环境状态、最近操作摘要和最近日志摘录。
- 本地运行文档补充第九阶段验证路径。

第九阶段不实现以下内容：

- 真实 SSH、SCP、rsync、远程服务器部署或生产环境连接。
- Docker Compose `down`、删除卷、删除镜像、清理网络等破坏性动作。
- 任意 shell 命令输入、环境变量注入、凭证读取或密钥管理。
- 多 Compose 文件覆盖、profile、scale、build、pull、restart 和高级编排参数。
- WebSocket 或 SSE 直连容器日志流。
- 数据库持久化、多成员权限、审批策略配置和组织级运行策略。

## 用户体验

运维在工作台中先保存部署目标，再进入「Compose 演示环境」区域：

- 选择部署目标。
- 选择已授权本地工作区。
- 填写 Compose 文件相对路径，例如 `deploy/compose.demo.yml`。
- 填写 Compose 项目名，例如 `matrixcode-demo`。
- 填写服务名，例如 `web`。

环境保存后，运维可以点击：

- 「校验配置」：运行 `docker compose config`，确认文件语义可以被 Compose 解析。
- 「启动演示」：运行 `docker compose up -d`，启动本地演示服务。
- 「停止演示」：运行 `docker compose stop`，停止服务但不删除资源。
- 「采集日志」：运行 `docker compose logs --tail 80 <service>`，采集最近日志摘录。

动作执行后，运维面板和右侧指标栏立即刷新。缺少 Docker、Compose 文件不合法、服务不存在或命令失败时，页面显示失败摘要和最近日志摘录，用户可以继续修正配置或重试。

## 服务端设计

新增 `deployment` 下的 Compose 运行态模型，沿用当前部署目标和工作台聚合边界。

### 领域对象

`ComposeEnvironment` 表示一个演示环境配置：

- `id`：环境编号。
- `projectId`：项目编号。
- `targetId`：部署目标编号。
- `workspaceId`：授权工作区编号。
- `composeFilePath`：工作区内的相对 Compose 文件路径。
- `projectName`：Docker Compose 项目名。
- `serviceName`：日志采样目标服务名。
- `status`：`CONFIGURED`、`VALIDATED`、`RUNNING`、`STOPPED` 或 `FAILED`。
- `createdAt`、`updatedAt`：创建和更新时间。

`ComposeOperationRecord` 表示一次运行态动作：

- `type`：`VALIDATE`、`START`、`STOP` 或 `LOGS`。
- `status`：`SUCCEEDED` 或 `FAILED`。
- `summary`：动作摘要。
- `logExcerpt`：截断后的输出或错误摘录。

`ComposeRuntimeView` 是工作台聚合视图：

- 环境编号、部署目标编号、状态、Compose 文件路径、项目名、服务名。
- 最近一次 Compose 操作。

### 应用服务

`ComposeEnvironmentService` 负责：

- 校验部署目标属于当前项目。
- 校验工作区已授权。
- 通过 `PathGuard` 解析 Compose 文件，保证路径不离开授权根目录。
- 校验文件后缀、项目名和服务名。
- 保存环境配置和最近 20 条操作记录。
- 调用 `ComposeRuntimeClient` 执行校验、启动、停止和日志采样。
- 根据动作结果更新环境状态。

### 运行时客户端

`ComposeRuntimeClient` 是可替换接口，默认实现为 `DockerComposeRuntimeClient`。默认实现使用 `ProcessBuilder` 参数数组执行命令：

- `docker compose -f <compose-file> -p <project-name> config`
- `docker compose -f <compose-file> -p <project-name> up -d`
- `docker compose -f <compose-file> -p <project-name> stop`
- `docker compose -f <compose-file> -p <project-name> logs --tail 80 <service-name>`

每次动作设置 30 秒超时，采集标准输出和错误输出，最多保留 2000 个字符作为日志摘录。Docker CLI 不存在、命令超时或退出码非 0 时，返回失败结果，不抛出未处理异常。

## API 设计

在现有项目工作台 API 下增加：

- `POST /api/projects/{projectId}/deployments/targets/{targetId}/compose-environments`
  - 请求体：`workspaceId`、`composeFilePath`、`projectName`、`serviceName`。
  - 响应：`ComposeEnvironment`。
- `POST /api/projects/{projectId}/compose-environments/{environmentId}/validate`
  - 请求体：`actorId`。
  - 响应：`ComposeOperationRecord`。
- `POST /api/projects/{projectId}/compose-environments/{environmentId}/start`
  - 请求体：`actorId`。
  - 响应：`ComposeOperationRecord`。
- `POST /api/projects/{projectId}/compose-environments/{environmentId}/stop`
  - 请求体：`actorId`。
  - 响应：`ComposeOperationRecord`。
- `POST /api/projects/{projectId}/compose-environments/{environmentId}/logs`
  - 请求体：`actorId`。
  - 响应：`ComposeOperationRecord`。

`GET /api/projects/{projectId}/workbench` 新增：

- `composeEnvironments`。
- `composeRuntimeViews`。

## 桌面端设计

`client.ts` 新增 Compose 相关类型和 API 客户端函数。

`OpsPanel` 在部署运行记录后新增「Compose 演示环境」表单：

- 复用当前部署目标选择。
- 选择授权工作区。
- 输入 Compose 文件相对路径、项目名和服务名。
- 保存后显示环境选择器和最近运行态摘要。
- 提供校验、启动、停止和日志采样按钮。

`InspectorPanel` 新增「Compose 运行态」指标卡：

- 展示环境状态、Compose 文件、项目名和服务名。
- 展示最近操作结果。
- 展示最近日志摘录，长行允许换行，不撑破右侧栏。

`App` 负责把工作台新增字段传入运维面板和指标面板，并在 Compose 动作结束后刷新工作台。

## 安全边界

第九阶段坚持专用通道和最小动作集合：

- 不接受任意命令字符串。
- 不使用 shell 拼接命令。
- 不执行 `down`、`rm`、`volume rm`、`image rm`、`prune` 等破坏性动作。
- 不读取或注入用户环境变量。
- 不允许 Compose 文件路径离开授权工作区。
- 不允许绝对路径作为 Compose 文件相对路径。
- 不允许非 YAML 后缀文件。
- 不允许空项目名、空服务名或包含空白和 shell 元字符的名称。
- 运行失败只记录结果，不自动重试。

## 测试策略

服务端：

- `ComposeEnvironmentServiceTest` 使用临时工作区和 fake `ComposeRuntimeClient`，验证配置、路径约束、状态流转、日志摘录和失败记录。
- `WorkbenchServiceTest` 验证工作台返回 Compose 环境和运行摘要。
- `WorkbenchControllerTest` 验证 Compose 配置接口编码、项目隔离和中文错误信息。
- `DockerComposeRuntimeClientTest` 只验证命令不可用时返回失败，不依赖本机 Docker。

桌面端：

- `client.test.ts` 验证新增 API 路径、目标编号和环境编号会正确 URL 编码。
- `App.test.tsx` 验证运维面板可以保存 Compose 环境，触发校验、启动、停止和日志采样，并在右侧指标栏展示状态和日志。

文档：

- `docs/development/local-run.md` 增加第九阶段验证说明。
- 阶段计划记录红灯、绿灯、全量验证和浏览器验证结果。

## 验收标准

- Compose 环境只能绑定到当前项目的部署目标和已授权工作区。
- 越界路径、绝对路径、非 YAML 文件、非法项目名和非法服务名会被中文错误拒绝。
- Compose 校验、启动、停止和日志采样动作都会生成操作记录。
- Docker CLI 不可用时动作记录为失败，服务端不崩溃。
- 工作台接口返回 Compose 环境和最近运行摘要。
- 桌面端运维面板和右侧指标栏展示 Compose 运行态。
- 服务端测试、桌面端测试、桌面构建和差异检查通过。

## 后续阶段入口

第十阶段可以继续扩展以下方向：

- Compose 日志 SSE 或 WebSocket 直连流。
- Compose `pull`、`build`、profile 和多文件覆盖。
- 真实 SSH 远程部署审批和远程 Compose 执行。
- Compose 操作进入长任务队列，支持取消、超时、重试和历史持久化。
- 组织级运行策略、用户权限和环境密钥托管。
