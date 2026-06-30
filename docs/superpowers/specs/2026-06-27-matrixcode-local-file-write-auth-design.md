# MatrixCode 本地文件写接口鉴权设计

## 背景

第 76 到 80 阶段已经把 actor token、Agent Runtime 写接口、本地命令写接口和编码智能体写接口纳入可信身份边界。本地文件写接口仍只依赖授权工作区和路径守卫，不校验请求身份。

`POST /api/projects/{projectId}/local-execution/files/write` 可以直接修改授权工作区内文件，风险等级高于只读文件列表、读取和 Git diff 摘要。它应先接入与本地命令和编码智能体写接口一致的身份校验。

## 推荐方案

收紧本地文件写接口：

- 请求体新增 `actorId`。
- `LocalExecutionController.writeFile(...)` 复用 `RequestActorResolver`。
- 进入 `LocalFileService.write(...)` 前校验请求身份与请求体 `actorId` 一致。
- 缺少请求身份返回 401。
- 请求身份与 `actorId` 不一致返回 403。
- 缺少 `actorId` 返回 400。
- 桌面端 `writeLocalFile(...)` 入参新增 `actorId`，并复用 `actorHeaders(input.actorId)`。

## 本阶段不做

- 不收紧文件列表、文件读取和 Git diff 摘要接口。
- 不收紧工作区授权接口。
- 不新增完整项目成员权限过滤。
- 不改变 `LocalFileService` 的路径守卫、文本大小限制、二进制限制或文件操作记录结构。
- 不新增 DDL。

## 验证标准

- 缺少身份头写文件返回 401。
- 请求身份与写文件 `actorId` 不一致返回 403。
- 缺少 `actorId` 写文件返回 400。
- 正常写文件必须携带请求身份且与 `actorId` 一致。
- 桌面端 `writeLocalFile(...)` 请求携带 `X-MatrixCode-User-Id` 和可选 Bearer token。
- 完整验证通过桌面端测试、桌面构建、服务端测试、真实运行检查、静态检查和敏感信息扫描。
