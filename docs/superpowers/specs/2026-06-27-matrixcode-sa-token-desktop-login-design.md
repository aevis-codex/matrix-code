# MatrixCode Sa-Token 桌面登录入口设计

## 背景

第 82 阶段已把服务端身份解析基座切到 Sa-Token 优先。第 83 阶段已提供登录、登出和当前会话 API。桌面端配置中心仍显示「身份令牌」签发体验，用户需要一个面向实际使用的登录态入口。

## 目标

- 在桌面端 API client 中接入 Sa-Token 登录、登出和当前会话接口。
- 在配置中心「身份」页签中把交互升级为登录、退出和当前登录态展示。
- 继续复用 `matrixcode.actorToken`、`matrixcode.actorTokenUserId`、`matrixcode.actorTokenExpiresAt` 本地存储键，保证第 78 到 81 阶段已接入的写接口不需要同步大改。
- 保持现阶段 bootstrap token 过渡凭证输入，但界面语义改为登录凭证；后续再替换为密码、SSO 或企业身份源。

## 非目标

- 不实现密码表、密码登录、SSO、邀请链接或企业身份源。
- 不新增 MySQL 表或 Flyway 迁移。
- 不接 Redis Session。
- 不清理旧 `/actor-token` 兼容接口。

## 推荐方案

采用「最小可上线过渡登录入口」：

1. `desktop/src/api/client.ts` 新增 `loginActorSession(...)`、`logoutActorSession(...)`、`loadActorSession(...)`。
2. `RoleAgentConfigDialog` 的身份页改为登录表单，提交调用 `/identity/auth/login`。
3. 登录成功后继续写入既有 token 本地存储键。
4. 退出登录调用 `/identity/auth/logout`，成功后清空本地 token。
5. 打开身份页时，如果本地已有 token，则调用 `/identity/auth/session` 校验当前会话；校验失败时清空本地状态。

## 权衡

- 优点：改动范围小，兼容现有写接口，能马上使用第 83 阶段后端 API。
- 缺点：登录凭证仍是 bootstrap token，不是最终普通用户认证方式。
- 后续演进：登录 UI 稳定后，将凭证输入替换为密码、SSO 或邀请凭证，并开启 `MATRIXCODE_AUTH_REQUIRE_SA_TOKEN=true`。

## 验收标准

- API client 测试覆盖登录、登出、当前会话三个接口地址、方法和请求头。
- 配置中心身份页测试覆盖登录成功写入 token、退出登录清空 token。
- 桌面端全量测试通过。
- 桌面端构建通过。
- 服务端全量测试仍通过，确认第 83 阶段后端接口未被破坏。
- 真实运行检查通过 MySQL、Milvus、Redis、RocketMQ。
- 敏感信息扫描通过。

## 回溯检查

- 与用户要求对齐：登录权限控制继续使用 Sa-Token。
- 与阶段 78 到 81 对齐：本地 token 存储键不变，写接口可继续透传 `Authorization: Bearer`。
- 与上线目标对齐：不保存真实密钥，不新增未注释 DDL，不引入新运行依赖。
