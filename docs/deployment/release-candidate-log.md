# MatrixCode 生产发布候选记录

## 2026-06-29：matrixcode-stage164-20260629220712

- Git 提交：`0d30afe`
- 发布归档：`dist/releases/matrixcode-stage164-20260629220712.tar.gz`
- SHA256：`361460c0b8f3fb4bc6c0c91c8abf71e7e1819f9ca77b15803cd8d3af3f5c790b`
- 发布候选报告：`dist/releases/matrixcode-stage164-20260629220712.release-candidate.md`
- 生产就绪聚合门禁：PASS
- 发布包 smoke：PASS
- 真实协议级检查：PASS
- 远程发布目标预检：SKIPPED，原因是未设置 `MATRIXCODE_REMOTE_RELEASE_TARGET`
- 远程发布 dry-run：SKIPPED，原因是未设置 `MATRIXCODE_REMOTE_RELEASE_TARGET`

## 安全边界

- 本记录只保存归档名、校验和、Git 提交和低敏状态。
- 本记录不保存数据库密码、API Key、Sa-Token、模型密钥、完整 prompt、模型响应、向量正文或工具输出。

## 2026-06-29：发布包 Jar 本机生产启动 Smoke

- 验证提交：`0d9fbd1`
- 发布目录：`dist/matrixcode-server`
- 服务端 Jar：`dist/matrixcode-server/matrixcode-server.jar`
- 临时端口：`28081`
- 真实预检：PASS，`RealSaTokenRedisSessionIntegrationTest` 1 条和 `RealRuntimeIntegrationTest` 2 条通过。
- 健康端点：PASS，`http://127.0.0.1:28081/actuator/health` 返回 `UP`。
- Info 端点：PASS，`http://127.0.0.1:28081/actuator/info` 可访问。
- 健康快照：`dist/health-snapshots/matrixcode-stage165-jar-production-smoke.md`。
- 停止方式：验证完成后手动中断临时本机进程，Tomcat graceful shutdown 完成。
- 当前边界：Nginx 负责 `/opt/matrixcode/desktop` 静态入口，Jar 根路径返回 404 不作为失败；真实远程上线仍需要 `MATRIXCODE_REMOTE_RELEASE_TARGET` 和目标服务器 SSH/systemd 权限。
