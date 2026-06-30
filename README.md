# MatrixCode

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Frontend](https://img.shields.io/badge/Desktop-Tauri%20%2B%20React-0f766e.svg)](https://tauri.app/)

MatrixCode 是一个多人实时协作的智能体控制台，面向产品、开发、测试、运维等角色，把需求冻结、文档交接、编码智能体、测试验收、部署运维、模型调用成本和上线门禁放到同一个工作台里。

## 项目目标

MatrixCode 的目标是做一个真实可上线运行的 Agent Console，而不是只停留在演示页面：

- **让每个角色都有自己的智能体工作台。** 产品、开发、测试、运维可以配置独立的系统提示词、用户提示词、模型供应商、字体、颜色和上下文策略。
- **把需求到上线的过程沉淀为可追踪资产。** PRD、界面说明、验收标准、编码交接文档、测试报告、Bug、部署记录和健康检查都进入统一项目链路。
- **降低多模型调用成本。** 参考 Codex、Claude Code 等编码智能体的工程模式，结合稳定 Prompt 分区、缓存命中率追踪、模型成本趋势和供应商缓存策略，减少无效 token 消耗。
- **支持真实基础设施。** 业务数据使用 MySQL，向量上下文使用 Milvus，登录态使用 Sa-Token + Redis，RocketMQ 作为项目事件跨节点中继的可选能力。

## 适用场景

- 小团队希望把需求、研发、测试、部署放进一个实时协作工作台。
- 企业内部希望建设可审计的智能体控制台，而不是让成员各自使用分散的聊天工具。
- AI 编码流程需要受控执行命令、文件变更、补丁应用和交付回溯。
- 多模型供应商并存，需要按角色切换模型，并持续观测成本、缓存命中和上下文使用情况。
- 项目进入上线阶段，需要生产配置门禁、健康探测、备份、发布包和回滚脚本。

## 解决的痛点

- **角色信息割裂：** 产品文档、开发交付、测试缺陷、运维环境分散在不同工具里。
- **智能体不可控：** Agent 生成命令、修改文件、调用模型缺少审批、审计和责任归属。
- **上线证据缺失：** 真实数据库迁移、健康检查、发布包、备份和回滚缺少统一门禁。
- **模型成本不可见：** 多模型调用缺少按角色、按运行、按项目维度的成本和缓存分析。
- **扩展困难：** 没有清晰的领域边界和项目图谱，后续扩展需要重新理解整套系统。

## 功能概览

- 四角色工作台：产品、开发、测试、运维。
- 文档中心：PRD、界面说明、验收标准、编码智能体交接文档可查看正文。
- 角色智能体配置：系统提示词、用户提示词、模型、预算、缓存策略、样式配置。
- 模型网关：千问、DeepSeek、Kimi、豆包等 OpenAI 兼容模型供应商接入。
- 向量上下文：支持 Milvus，默认 embedding 模型可配置为千问 `text-embedding-v4`。
- 身份权限：用户名密码登录，Sa-Token 会话，`admin` 超级管理员可创建用户和设置权限。
- 本地执行代理：工作区授权、命令审批、执行日志、文件读写、Git diff 摘要。
- Agent Runtime：运行记录、事件追踪、失败重试、认领、用户审计和后台调度。
- 部署运维：部署目标、健康检查、Compose 环境、发布包、远程发布脚本和回滚脚本。
- 生产门禁：真实 MySQL/Flyway、Milvus、Redis、RocketMQ 预检和上线前 readiness 脚本。

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot 3.5、Sa-Token、MyBatis-Plus、Flyway |
| 前端 | React、TypeScript、Vite、Tauri |
| 数据库 | MySQL 8.x |
| 向量数据库 | Milvus |
| 缓存 / Session | Redis |
| 消息中间件 | RocketMQ（默认预留，事件中继按需启用） |
| 模型供应商 | OpenAI 兼容接口，已适配千问、DeepSeek、Kimi、豆包 |

## 目录结构

```text
.
├── desktop/                 # React + Tauri 客户端
├── server/                  # Spring Boot 后端服务
├── scripts/                 # 本地、真实运行、生产门禁、发布和备份脚本
├── ops/                     # systemd、Nginx、生产 env 模板
├── docs/                    # 开发、部署、上线和历史阶段文档
├── bugs/                    # 阶段性问题记录
├── .env.example             # 开源安全的本地环境变量模板
└── README.md
```

## 部署前准备

### 基础环境

- JDK 21
- Maven 3.9+
  - 本项目开发环境默认 Maven 路径：`/Users/Masons/Ai/Maven`
  - 本项目开发环境默认本地仓库：`/Users/Masons/Ai/Maven_Ai_Store`
  - 开源用户也可以使用系统 `mvn`，命令里的 Maven 路径按实际环境替换即可。
- Node.js 22+
- MySQL 8.x
- Milvus 2.x
- Redis 6+
- RocketMQ 5.x（可选；未启用事件中继时只做预留）

### 模型密钥

至少准备一个 OpenAI 兼容模型供应商的 API Key。当前模板包含：

- 千问：`MATRIXCODE_QWEN_API_KEY`
- DeepSeek：`MATRIXCODE_DEEPSEEK_API_KEY`
- Kimi：`MATRIXCODE_KIMI_API_KEY`
- 豆包：`MATRIXCODE_DOUBAO_API_KEY`

真实密钥只能写入 `.env.local` 或服务器私有环境变量文件，不能提交到 Git。

## 配置方式

公开仓库只提交 `.env.example` 和 `ops/env/matrixcode.env.example`。真实地址、账号、密码、API Key 放在本地 `.env.local`：

```bash
cp .env.example .env.local
```

然后编辑 `.env.local`：

```bash
MATRIXCODE_MYSQL_HOST=127.0.0.1
MATRIXCODE_MYSQL_PORT=3306
MATRIXCODE_PERSISTENCE_JDBC_USERNAME=matrixcode_user
MATRIXCODE_PERSISTENCE_JDBC_PASSWORD=your_password

MATRIXCODE_MILVUS_HOST=127.0.0.1
MATRIXCODE_REDIS_HOST=127.0.0.1
MATRIXCODE_ROCKETMQ_NAME_SERVER=127.0.0.1:9876

MATRIXCODE_AUTH_ADMIN_INITIAL_PASSWORD=change_this_before_use
MATRIXCODE_QWEN_API_KEY=your_qwen_key
```

注意事项：

- `.env.local` 已被 `.gitignore` 忽略，适合保存本地真实配置。
- `.env.example` 只能放占位值，不能出现真实服务器 IP、数据库密码或模型密钥。
- 生产环境建议使用 `/etc/matrixcode/matrixcode.env` 这类服务器私有文件。
- `MATRIXCODE_AUTH_ADMIN_INITIAL_PASSWORD` 只用于初始化 `admin` 超级管理员；已有可用密码时不会被启动覆盖。

## 本地运行

### 1. 检查真实运行配置

```bash
bash scripts/check-real-runtime.sh .env.local
```

检查内容包括：

- 必填环境变量
- MySQL TCP 连通
- Milvus TCP 连通
- Redis TCP 连通
- RocketMQ TCP 连通（按配置）
- 生产门禁配置（开启时）

### 2. 启动后端

```bash
/Users/Masons/Ai/Maven/bin/mvn \
  -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store \
  -pl server \
  spring-boot:run
```

使用系统 Maven 时：

```bash
mvn -pl server spring-boot:run
```

后端默认地址：

```text
http://127.0.0.1:8080
```

健康检查：

```bash
curl http://127.0.0.1:8080/actuator/health
```

### 3. 启动浏览器前端

```bash
npm --prefix desktop install
VITE_MATRIXCODE_SERVER_URL=http://127.0.0.1:8080 npm --prefix desktop run dev
```

浏览器访问：

```text
http://127.0.0.1:5173
```

## 客户端使用方式

MatrixCode 客户端位于 `desktop/`，基于 Tauri + React。

开发模式：

```bash
npm --prefix desktop run dev
```

构建前端产物：

```bash
npm --prefix desktop run build
```

Tauri 客户端命令可在 `desktop/src-tauri` 下按 Tauri 官方流程继续扩展。当前仓库重点保证 Web 工作台、后端 API、真实运行门禁和生产部署脚本闭环。

## 浏览器使用方式

浏览器模式适合开发、验收和演示：

1. 启动后端。
2. 启动 `desktop` 的 Vite 开发服务。
3. 打开 `http://127.0.0.1:5173`。
4. 使用 `admin` 用户登录。
5. 进入「配置」设置角色智能体、模型供应商、成员和权限。
6. 进入「文档」查看 PRD、界面说明、验收标准和交接文档正文。
7. 在四个角色工作区中完成需求、开发、测试、验收和部署流转。

如果后端不是 `8080` 端口，需要设置：

```bash
VITE_MATRIXCODE_SERVER_URL=http://127.0.0.1:18080 npm --prefix desktop run dev
```

## 登录和权限

- 登录方式：用户名 + 密码。
- 会话框架：Sa-Token。
- 超级管理员：`admin`。
- `admin` 登录后可以在配置中心创建用户并设置项目角色。
- 角色权限包括产品、开发、测试、运维、项目负责人等项目内权限边界。

首次启动时需要配置：

```bash
MATRIXCODE_AUTH_ADMIN_INITIAL_PASSWORD=change_this_before_use
```

上线前必须换成强密码，并妥善保存到服务器私有环境变量中。

## 生产部署

生产部署资产位于 `ops/`：

- `ops/systemd/matrixcode.service`：systemd 服务模板。
- `ops/nginx/matrixcode.conf`：HTTP 反向代理模板。
- `ops/nginx/matrixcode-https.conf`：HTTPS 反向代理模板。
- `ops/bin/run-matrixcode-server.sh`：Jar 启动脚本。
- `ops/env/matrixcode.env.example`：生产环境变量模板。

常用生产验证：

```bash
bash scripts/verify-production-deployment-assets.sh
bash scripts/verify-production-readiness.sh
bash scripts/build-production-server.sh
bash scripts/package-production-release.sh
```

生产启动前建议开启：

```bash
MATRIXCODE_PRODUCTION_CHECK=true
MATRIXCODE_PROTOCOL_CHECK=true
MATRIXCODE_AUTH_REQUIRE_SA_TOKEN=true
MATRIXCODE_AUTH_SESSION_STORE=redis
MATRIXCODE_PERSISTENCE_JDBC_MIGRATE_ON_STARTUP=true
```

## 数据库和迁移

- 正式业务库使用 MySQL。
- ORM 使用 MyBatis-Plus。
- Schema 迁移使用 Flyway。
- 表和字段迁移脚本需要写清楚中文注释。
- 本项目不把 H2 作为正式运行数据库；测试场景可按需使用内存数据库或兼容测试配置。

## 验证命令

```bash
/Users/Masons/Ai/Maven/bin/mvn \
  -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store \
  -pl server test

npm --prefix desktop test
npm --prefix desktop run build
bash scripts/check-real-runtime.sh .env.local
bash scripts/verify-production-readiness.sh
```

开源用户使用系统 Maven 时：

```bash
mvn -pl server test
```

## 安全注意事项

- 不要提交 `.env.local`、`.env`、私钥、数据库密码、模型 API Key。
- 如果密钥曾经推送到远程仓库，应立即轮换，不能只靠删除文件解决。
- `.env.example` 和 `ops/env/matrixcode.env.example` 只能保留占位值。
- 前端浏览器本地状态不应视为安全边界，服务端接口必须执行 Sa-Token 和项目权限校验。
- LLM 输出必须当作不可信输入处理，不能直接执行为 Shell、SQL 或 HTML。
- 编码智能体涉及本地命令和文件写入时，必须走工作区授权、审批和审计链路。

## 文档入口

- [本地运行与生产门禁](./docs/development/local-run.md)
- [生产部署运行手册](./docs/deployment/production-runbook.md)
- [生产备份与恢复](./docs/deployment/backup-restore.md)
- [TLS 与真实域名入口](./docs/deployment/tls-and-domain.md)
- [生产健康探测与告警](./docs/deployment/health-alerting.md)
- [发布候选记录](./docs/deployment/release-candidate-log.md)

## 贡献指南

欢迎提交 Issue 和 Pull Request。建议遵循以下原则：

- 一个 PR 只解决一个问题。
- 涉及接口、权限、数据结构或部署脚本时，同时补测试和文档。
- 迁移脚本必须包含表和字段注释。
- 不提交真实密钥、真实服务器地址或个人环境配置。
- 提交前运行后端测试、前端测试和敏感信息扫描。

## 开源协议

MatrixCode 使用 [MIT License](./LICENSE) 开源。
