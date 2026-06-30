# MatrixCode 编码智能体执行桥接设计

## 背景

第 22 阶段已经提供编码智能体任务协议，但任务只停留在计划层。系统仍缺少一个受控桥接层，把“上下文、计划、文件、代码、测试、diff、交付”步骤映射到本地执行、审批、测试和回溯能力。

本阶段不实现自动代码修改。原因是代码写入、命令执行和测试运行必须先进入既有本地执行与审批边界，否则会绕过第五、六阶段建立的安全模型。

## 推荐方案

新增“编码智能体执行准备”后端能力：

- 输入目标、角色、授权工作区、执行人和测试命令。
- 先复用 `CodingAgentTaskService` 生成结构化编码步骤。
- 校验 `workspaceId` 必须属于当前项目且已授权。
- 将测试命令提交给 `LocalCommandService`，由既有 `ApprovalPolicy` 决定直接排队、待审批或拒绝。
- 调用 `LocalGitDiffService` 采集当前 diff 摘要，作为执行前基线。
- 返回每个编码步骤的本地执行映射状态。

## API 设计

新增接口：

```http
POST /api/projects/{projectId}/roles/{role}/coding-agent/execution-plans
```

请求体：

```json
{
  "goal": "实现登录接口",
  "workspaceId": "workspace-main",
  "actorId": "user-dev",
  "testCommand": "git status"
}
```

响应体：

- `task`：原始编码智能体任务计划。
- `executionSteps`：每个步骤映射到本地能力后的执行状态。
- `testCommandTask`：由本地执行命令服务创建的测试命令任务。
- `gitDiffSummary`：当前工作区 diff 摘要。

## 执行状态

执行步骤状态使用服务端枚举表达：

- `READY`：步骤已映射到可用本地能力，但本阶段不自动执行。
- `REVIEW_REQUIRED`：需要人审查，例如计划审查、文件选择、交付回溯。
- `APPROVAL_REQUIRED`：需要人工审批或显式授权，例如代码写入、危险或未知测试命令。
- `SUBMITTED`：测试命令已进入本地执行队列。
- `CAPTURED`：已采集快照类信息，例如 Git diff。

## 安全边界

- 未授权工作区直接拒绝。
- 代码编辑步骤只标记为 `APPROVAL_REQUIRED`，不调用文件写入。
- 测试命令统一通过 `LocalCommandService.submit`，不能绕过 `ApprovalPolicy`。
- 高风险命令保持待审批或拒绝，不能由编码智能体自行批准。
- 响应不包含密钥、密码、环境变量值或文件内容。

## 验收标准

- 服务层能为开发角色生成执行准备报告。
- 未授权工作区不能生成执行准备报告。
- 控制器能暴露 `execution-plans` 接口。
- 测试命令映射到本地执行任务，并体现 `APPROVAL_REQUIRED` 或 `SUBMITTED`。
- Git diff 摘要被采集并出现在响应中。
- 服务端全量测试通过。
- 阶段完成后更新 Obsidian `MatrixCode` 项目图谱。
