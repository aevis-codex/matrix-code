# MatrixCode 受控编码智能体 Patch 应用设计

## 背景

第 24 阶段已经让编码智能体生成执行准备计划，第 25 阶段已经把执行准备报告接入桌面端开发角色工作区。下一步需要让开发角色在明确审批后应用小范围代码变更，但不能绕过工作区授权、路径守卫、文件大小限制、二进制文件拒绝和 Git diff 审查。

## 目标

实现一个受控 patch 应用切片：开发角色可以提交一个整文件文本 patch，请求中必须包含当前期望内容、目标内容和显式审批标记；服务端确认当前文件仍等于期望内容后写入目标内容，并返回写入结果和 Git diff 摘要。

## 非目标

- 不做自动生成代码 patch。
- 不做多文件批量 patch。
- 不解析 unified diff。
- 不自动提交 Git。
- 不绕过用户确认或审批边界。

## 推荐方案

新增编码智能体 patch API：

```text
POST /api/projects/{projectId}/roles/{role}/coding-agent/patches
```

请求字段：

```json
{
  "workspaceId": "workspace-1",
  "actorId": "user-dev",
  "relativePath": "desktop/src/App.tsx",
  "expectedContent": "旧内容",
  "nextContent": "新内容",
  "summary": "修复执行准备入口",
  "approved": true
}
```

服务端行为：

1. 校验 `approved` 必须为 `true`。
2. 使用 `LocalFileService.read` 读取目标文件，继承授权工作区、路径守卫、读取大小和二进制拒绝。
3. 当前内容必须等于 `expectedContent`，否则拒绝并要求重新生成 patch。
4. 使用 `LocalFileService.write` 写入 `nextContent`，继承写入大小限制和操作记录。
5. 使用 `LocalGitDiffService.capture` 返回应用后的 Git diff 摘要。

## 桌面端交互

- 在开发角色执行准备报告下方增加“受控 Patch 应用”表单。
- 表单包含相对路径、期望当前内容、目标内容、变更说明和确认复选框。
- 只有确认复选框勾选、路径和内容完整时才能提交。
- 成功后显示写入字节数、变更文件数量和 diff stat。

## 安全边界

- `approved: true` 是服务端硬门禁，避免自动工具直接应用 patch。
- `expectedContent` 是并发防护，避免覆盖人工或其他智能体刚刚修改的文件。
- 文件读取和写入继续复用 `LocalFileService`，不新开文件系统入口。
- 不允许二进制文件、大文件和越权路径。
- 返回 diff 摘要，用于后续审查和交付回溯。

## 测试策略

- 服务层 TDD：未审批拒绝、当前内容不匹配拒绝、成功写入并返回 Git diff。
- 控制器 TDD：patch API 可被开发角色调用并返回写入结果。
- 桌面端 TDD：开发角色填写 patch 表单并确认后调用 API，成功后展示结果。
- 全量验证：服务端测试、桌面端测试、桌面端构建、diff 检查和密钥扫描。
