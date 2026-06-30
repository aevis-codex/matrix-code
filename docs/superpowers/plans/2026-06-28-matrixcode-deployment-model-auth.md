# 第 88 阶段计划：部署配置与模型绑定权限

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将部署配置、部署运行记录、Compose 环境配置、模型供应商配置和角色模型绑定接入 Sa-Token 登录态与项目权限。

**架构：** 复用 `RequestActorResolver` 解析当前请求身份，复用 `ProjectMemberPermissionGuard` 区分项目成员权限和项目管理权限。部署类操作要求 ACTIVE 项目成员；模型供应商配置和角色模型绑定要求 OWNER、ADMIN、MAINTAINER。

**技术栈：** Spring Boot 3、Sa-Token、MockMvc、React/Vitest。

---

## 成功标准

- 缺少身份访问部署目标配置、健康检查、部署操作、Compose 环境配置返回 401。
- 非项目成员访问上述部署类接口返回 403。
- 带 `actorId` 的部署健康检查和部署操作要求请求身份与 `actorId` 一致，不一致返回 403。
- 缺少身份访问模型供应商配置和角色模型绑定返回 401。
- 非项目管理角色访问模型供应商配置和角色模型绑定返回 403。
- 桌面端部署类 API、模型绑定 API 透传当前操作者和本地 Bearer token，并兼容旧 serverUrl 调用。
- 后端定向测试、前端定向测试、全量回归、真实运行环境检查和敏感信息扫描通过。

## 执行清单

- [x] 添加后端红灯用例覆盖部署类接口 401/403 和 actor 不一致。
- [x] 添加后端红灯用例覆盖模型供应商配置、角色模型绑定的 401/403/成功。
- [x] 添加前端红灯用例覆盖部署类 API 和模型绑定 API 身份头透传。
- [x] 在 `WorkbenchController` 中保护部署目标、健康检查、部署操作和 Compose 环境配置。
- [x] 在 `ModelGatewayController` 中保护模型供应商配置和角色模型绑定。
- [x] 更新桌面端 API 和调用点，兼容旧 serverUrl 调用并支持 actor 身份。
- [x] 跑后端定向测试与前端定向测试。
- [x] 跑全量回归、敏感信息扫描、真实运行环境检查。
- [x] 更新 Obsidian MatrixCode 项目图谱并回溯阶段偏差。

## 验证记录

- 后端定向测试：`WorkbenchControllerTest,ModelGatewayControllerTest` 通过，30 条测试，0 failures，0 errors。
- 前端定向测试：`client.test.ts,App.test.tsx` 通过，114 条测试，0 failures。
- 桌面端全量：`npm --prefix desktop test` 通过，120 条测试。
- 桌面端构建：`npm --prefix desktop run build` 通过。
- 服务端全量：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -pl server test` 通过，479 条测试，0 failures，0 errors，7 skipped。
- 真实运行检查：MySQL、Milvus、Redis、RocketMQ 连通性通过。
- 静态检查：`git diff --check`、`git diff --cached --check` 通过。
- 安全扫描：仓库与 Obsidian 图谱敏感信息扫描通过。

## 回溯结论

- 本阶段与最初“多人实时协作智能体控制台、角色可配置、真实可上线、Sa-Token 权限控制”的目标一致。
- 部署目标配置、健康检查、部署操作、Compose 环境配置、模型供应商配置和角色模型绑定不再匿名开放。
- 仍需继续收紧 Compose 校验、启动、停止和日志等运行态动作权限，并推进 Sa-Token Session/Redis 治理。
