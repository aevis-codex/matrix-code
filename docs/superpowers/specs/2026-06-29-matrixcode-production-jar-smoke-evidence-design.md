# MatrixCode 第 165 阶段：发布包 Jar 生产启动 Smoke 设计

## 目标

验证已生成发布目录中的 `matrixcode-server.jar` 可以在真实生产配置下启动、连接真实基础设施并返回健康状态，补齐第 164 阶段之后的本机生产启动证据。

## 决策

- 使用 `dist/matrixcode-server/bin/run-matrixcode-server.sh` 启动发布包内 Jar，而不是只验证源码模式 `spring-boot:run`。
- 使用临时端口 `28081` 避免影响当前 `8080` 和 `18080` 上已有进程。
- 启动前保留生产预检，确认真实 MySQL/Flyway、Milvus、Redis Session 和基础连通性仍可用。
- 验证完成后中断临时进程，避免遗留后台服务。
- 仓库只记录低敏摘要，不提交 `dist/health-snapshots/` 产物和 `.env.local`。

## 验证

- 发布包 Jar 启动命令：`MATRIXCODE_APP_HOME=... MATRIXCODE_ENV_FILE=... MATRIXCODE_SERVER_JAR=... SERVER_PORT=28081 bash dist/matrixcode-server/bin/run-matrixcode-server.sh`
- 真实预检：`RealSaTokenRedisSessionIntegrationTest` 1 条通过，`RealRuntimeIntegrationTest` 2 条通过。
- 健康端点：`curl -fsS --max-time 5 http://127.0.0.1:28081/actuator/health` 返回 `{"status":"UP"}`。
- Info 端点：`curl -fsS --max-time 5 http://127.0.0.1:28081/actuator/info` 返回 `{}`。
- 健康快照：`scripts/capture-production-health-snapshot.sh` 生成低敏 Markdown 快照。
- 停止验证：`28081` 端口释放。

## 与最初需求对齐

- 对齐“真实可上线运行”：本阶段证明发布包 Jar 能在真实生产配置下启动并完成健康探测。
- 对齐安全边界：只记录 env SHA256、布尔配置和端点状态，不记录真实密钥。
- 当前缺口：真实远程上线仍缺少远程目标配置和 SSH/systemd 权限；多实例事件中继仍等待 RocketMQ topic 发送门禁通过。
